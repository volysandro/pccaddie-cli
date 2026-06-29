#!/usr/bin/env bb
;; pccaddie.bb — fetch tee-time availability from the PC CADDIE mobile portal.
;;
;; Usage:
;;   ./pccaddie.bb --search '<keyword>'        List clubs matching keyword (+ ids)
;;   ./pccaddie.bb show-courses [--id <ID>]    List a club's courses (+ course ids)
;;   ./pccaddie.bb show-teetimes [opts]        Show tee-time availability
;;
;; --id <ID> overrides the configured default club for that single run only;
;; it never changes config.edn. Edit :default-club in config.edn to change it.
;;
;; show-teetimes options:
;;   --date <YYYY-MM-DD|today|tomorrow|+N>     Day to query (default: today)
;;   --course <id|name>                        Only this course (e.g. "9", "6", ALIAS|9LO)
;;   --id <ID>                                 Query a club other than the default
;;   --free                                    Only show times with free seats
;;   --no-color                                Disable ANSI colors
;;
;; The default club, login and portal URL live in config.edn next to this script.

(require '[babashka.http-client :as http]
         '[babashka.fs :as fs]
         '[clojure.string :as str]
         '[clojure.edn :as edn]
         '[clojure.walk :as walk]
         '[clojure.pprint :as pprint]
         '[cheshire.core :as json])

;; ---------------------------------------------------------------------------
;; Config
;; ---------------------------------------------------------------------------

(def script-dir (-> *file* fs/absolutize fs/parent str))
(def config-path (str (fs/path script-dir "config.edn")))

(def default-config
  {:base-url     "https://mobile.pccaddie.net/clubs/pcco/app.php"
   :user-agent   "Mozilla/5.0 (Linux; Android 10; Mobile)"
   :login        {:user "" :password ""}
   :default-club 214})

(defn load-config []
  (let [cfg (if (fs/exists? config-path)
              (merge default-config (edn/read-string (slurp config-path)))
              (do (spit config-path (with-out-str (pprint/pprint default-config)))
                  default-config))]
    (update-in cfg [:login :password]
               #(or (not-empty (System/getenv "PCCADDIE_PASSWORD")) %))))

;; ---------------------------------------------------------------------------
;; HTTP session
;; ---------------------------------------------------------------------------

(defn make-session
  "Logs in and returns a context map {:client :config} carrying the session cookies."
  [config]
  (let [client (http/client (assoc http/default-client-opts
                                   :cookie-handler (java.net.CookieManager.)
                                   :follow-redirects :always))
        ctx    {:client client :config config}
        ua     (:user-agent config)
        {:keys [user password]} (:login config)]
    ;; prime PHP session cookies
    (http/get (:base-url config)
              {:client client :query-params {:cat "login"} :headers {"User-Agent" ua}})
    ;; authenticate
    (let [resp (http/post (:base-url config)
                          {:client      client
                           :query-params {:cat "start"}
                           :headers     {"User-Agent" ua
                                         "Content-Type" "application/x-www-form-urlencoded"}
                           :form-params {"service"     "login"
                                         "rq[login]"    user
                                         "rq[password]" password}})]
      (when (str/includes? (:body resp) "Login fehlgeschlagen")
        (throw (ex-info "Login failed — check :login in config.edn (or PCCADDIE_PASSWORD)." {})))
      ctx)))

(defn fetch
  "GET app.php with the given query params (a map). Returns the response body."
  [{:keys [client config]} params]
  (:body (http/get (:base-url config)
                   {:client       client
                    :query-params params
                    :headers      {"User-Agent" (:user-agent config)}})))

(defn post
  "POST app.php with query params and url-encoded form params. Returns the body."
  [{:keys [client config]} params form]
  (:body (http/post (:base-url config)
                    {:client       client
                     :query-params params
                     :headers      {"User-Agent"   (:user-agent config)
                                    "Content-Type" "application/x-www-form-urlencoded"}
                     :form-params  form})))

;; ---------------------------------------------------------------------------
;; HTML / embedded-JSON parsing
;; ---------------------------------------------------------------------------

(def named-entities
  "Named HTML entities that show up in PC CADDIE text (club names etc.).
   Western-European letters plus the handful of markup entities. &amp; is not
   listed here — it is decoded last, separately, so it can't re-introduce others."
  {"quot" "\"" "lt" "<" "gt" ">" "nbsp" " " "apos" "'"
   "Auml" "Ä" "auml" "ä" "Ouml" "Ö" "ouml" "ö" "Uuml" "Ü" "uuml" "ü" "szlig" "ß"
   "Aacute" "Á" "aacute" "á" "Eacute" "É" "eacute" "é" "Egrave" "È" "egrave" "è"
   "Agrave" "À" "agrave" "à" "Ccedil" "Ç" "ccedil" "ç" "Uacute" "Ú" "uacute" "ú"
   "Iacute" "Í" "iacute" "í" "Oacute" "Ó" "oacute" "ó" "ntilde" "ñ" "Ntilde" "Ñ"
   "ocirc" "ô" "ecirc" "ê" "acirc" "â" "icirc" "î" "ucirc" "û"})

(defn html-decode
  "Decode HTML entities found in PC CADDIE's embedded data-props JSON and text.
   Numeric entities are decoded generically so any character (e.g. &#x2B; -> +)
   is handled; common named entities are decoded from `named-entities`. &amp; is
   decoded last so it can't re-introduce other entities."
  [s]
  (-> s
      (str/replace #"&#x([0-9A-Fa-f]+);" (fn [[_ h]] (str (char (Integer/parseInt h 16)))))
      (str/replace #"&#(\d+);"           (fn [[_ d]] (str (char (Integer/parseInt d)))))
      (str/replace #"&([A-Za-z]+);"      (fn [[m name]] (get named-entities name m)))
      (str/replace "&amp;" "&")))

(defn- walk-find-combo
  "Return the :data of a ComboBox node with the given identifier in a parsed tree."
  [tree id]
  (let [result (atom nil)]
    (walk/postwalk
     (fn [x]
       (when (and (map? x)
                  (= "ComboBox" (:component x))
                  (= id (get-in x [:props :identifier])))
         (reset! result (get-in x [:props :data])))
       x)
     tree)
    @result))

(defn find-combo-data
  "Find the option list of a PC CADDIE ComboBox embedded in a page's data-props.
   Returns a vector of {:id :value :name :href ...} maps, or nil."
  [html id]
  (let [needle (str "&quot;identifier&quot;&#x3A;&quot;" id "&quot;")]
    (some (fn [[_ raw]]
            (when (str/includes? raw needle)
              (walk-find-combo (json/parse-string (html-decode raw) true) id)))
          (re-seq #"data-props=\"([^\"]*)\"" html))))

;; Each tee-time row is rendered as
;;   id="pcco-timetable-time-0710" data-time="07:10" data-status="bookable" data-seat_bookable="1"
(def slot-re
  #"(?s)id=\"pcco-timetable-time-\d+\"\s+data-time=\"(\d{1,2}:\d{2})\"\s+data-status=\"([^\"]*)\"\s+data-seat_bookable=\"(\d+)\"")

(defn parse-slots
  "Parse tee-time slots from a timetable page body."
  [html]
  (->> (re-seq slot-re html)
       (map (fn [[_ t s b]] {:time t :status s :free (parse-long b)}))))

;; ---------------------------------------------------------------------------
;; Domain queries
;; ---------------------------------------------------------------------------

(defn club-id-from-href [href]
  (some-> (re-find #"club=(\d+)" (str href)) second))

(defn list-clubs
  "Return all clubs known to the portal: [{:id :name :number :country}]."
  [ctx]
  (->> (find-combo-data (fetch ctx {:cat "clubselect"}) "search_club")
       (keep (fn [c]
               (when-let [id (club-id-from-href (:href c))]
                 {:id id :name (:name c) :number (:number c) :country (:country c)})))))

(defn list-courses
  "Return a club's courses: {:courses [{:alias :name}] :selected <alias>}.
   `body` is an already-fetched timetable page (so callers can reuse it)."
  [body]
  (let [data (find-combo-data body "area_filter")]
    {:courses  (mapv (fn [a] {:alias (:value a) :name (:name a)}) data)
     :selected (some (fn [a] (when (:selected a) (:value a))) data)}))

(defn fetch-timetable
  "Fetch the timetable page for a club, optional course alias and date (YYYY-MM-DD)."
  [ctx club-id {:keys [alias date]}]
  (fetch ctx (cond-> {:club club-id :cat "tt_timetable_course"}
               date  (assoc :date (str "DAY|" date))
               alias (assoc :alias alias))))

;; ---------------------------------------------------------------------------
;; Dates
;; ---------------------------------------------------------------------------

(defn resolve-date
  "Turn a user date string into YYYY-MM-DD. Accepts nil/today/tomorrow/+N/ISO date."
  [s]
  (let [today (java.time.LocalDate/now)]
    (str
     (cond
       (or (nil? s) (= s "today")) today
       (= s "tomorrow")            (.plusDays today 1)
       (re-matches #"\+\d+" s)     (.plusDays today (parse-long (subs s 1)))
       (re-matches #"\d{4}-\d{2}-\d{2}" s) (java.time.LocalDate/parse s)
       :else (throw (ex-info (str "Bad --date: " s) {}))))))

;; ---------------------------------------------------------------------------
;; Output
;; ---------------------------------------------------------------------------

(def ^:dynamic *color* true)
(defn- c [code s] (if *color* (str "\033[" code "m" s "\033[0m") s))
(defn green [s] (c "32" s))
(defn dim   [s] (c "2"  s))
(defn bold  [s] (c "1"  s))
(defn yellow [s] (c "33" s))

(def status-label
  {"bookable"   "frei"
   "occupied"   "belegt"
   "locked"     "gesperrt"
   "block-time" "Blockzeit"
   "past-time"  "vorbei"})

(defn format-slot [{:keys [time status free]}]
  (let [label (status-label status status)]
    (cond
      (and (= status "bookable") (pos? free))
      (str (bold time) "  " (green (format "%d frei" free)))
      (= status "bookable") ;; bookable but 0 seats -> effectively full
      (str (dim time) "  " (dim "voll"))
      :else
      (str (dim time) "  " (dim label)))))

(defn print-course [{:keys [name alias]} slots {:keys [only-free]}]
  (let [shown (if only-free (filter #(and (= "bookable" (:status %)) (pos? (:free %))) slots) slots)
        free-times (count (filter #(and (= "bookable" (:status %)) (pos? (:free %))) slots))
        free-seats (reduce + (map :free (filter #(= "bookable" (:status %)) slots)))]
    (println)
    (println (bold (str name)) (dim (str "(" alias ")")))
    (if (empty? slots)
      (println "  " (dim "keine Startzeiten"))
      (do
        (doseq [s shown] (println "  " (format-slot s)))
        (println "  " (dim (format "→ %d Startzeiten, %d frei buchbar (%d Plätze)"
                                    (count slots) free-times free-seats)))))))

;; ---------------------------------------------------------------------------
;; Commands
;; ---------------------------------------------------------------------------

(defn cmd-search [ctx keyword]
  (let [kw (str/lower-case keyword)
        hits (->> (list-clubs ctx)
                  (filter #(str/includes? (str/lower-case (:name %)) kw))
                  (sort-by :name))]
    (if (empty? hits)
      (println "No clubs matched" (pr-str keyword))
      (do
        (println (bold (format "%d club(s) matching %s:" (count hits) (pr-str keyword))))
        (doseq [{:keys [id name number]} hits]
          (println (format "  %-6s %s %s" id name (dim (str "#" number)))))
        (println)
        (println (dim "Use:  ./pccaddie.bb --id <ID>   to set one as default."))))))

(defn cmd-show-courses [ctx club-id]
  (let [{:keys [courses]} (list-courses (fetch-timetable ctx club-id {}))]
    (if (empty? courses)
      (println (yellow (str "No selectable courses found for club " club-id
                            " (it may offer a single course).")))
      (do
        (println (bold (format "%d course(s) for club %s:" (count courses) club-id)))
        (doseq [{:keys [alias name]} courses]
          (println (format "  %-14s %s" alias name)))
        (println)
        (println (dim (str "Use:  ./pccaddie.bb show-teetimes --course '" (:alias (first courses)) "'")))))))

(defn match-courses
  "Filter courses by a user term. Matches a substring of the course name, or the
   alias code exactly (with or without the ALIAS| prefix), case-insensitive.
   Alias matching is exact so that e.g. \"6\" matches \"6-Loch\" by name without
   also matching the alias code \"ALIAS|0601\"."
  [courses term]
  (if (str/blank? (str term))
    courses
    (let [t     (str/lower-case (str/trim term))
          alias #(str/lower-case (str %))]
      (filter (fn [{:keys [name] a :alias}]
                (or (str/includes? (str/lower-case name) t)
                    (= (alias a) t)
                    (= (alias a) (str "alias|" t))))
              courses))))

(defn cmd-show-teetimes [ctx club-id {:keys [date course free]}]
  (let [iso     (resolve-date date)
        ;; first fetch doubles as the course list AND the default course page
        base    (fetch-timetable ctx club-id {:date iso})
        {:keys [courses selected]} (list-courses base)
        courses (if (seq courses) courses [{:alias nil :name "Startzeiten"}])
        wanted  (match-courses courses course)]
    (when (and course (empty? wanted))
      (println (yellow (str "No course matches " (pr-str course) ". Available:")))
      (doseq [{:keys [alias name]} courses] (println (format "  %-14s %s" alias name)))
      (System/exit 2))
    (println (bold (format "Tee times — club %s — %s" club-id iso)))
    (doseq [crs wanted]
      (let [body (if (= (:alias crs) selected)
                   base ;; reuse the page we already fetched
                   (fetch-timetable ctx club-id {:alias (:alias crs) :date iso}))]
        (print-course crs (parse-slots body) {:only-free free})))))

;; ---------------------------------------------------------------------------
;; Scorecards (the "Online Scorekarte" tab — club-independent: the list holds
;; every scorecard the logged-in player has recorded, across all courses/clubs)
;; ---------------------------------------------------------------------------

(defn- strip-tags
  "Remove HTML tags, decode entities and trim — turns a <td> cell into text."
  [s]
  (-> (str s) (str/replace #"(?s)<[^>]*>" "") html-decode str/trim))

(defn fetch-scorecard-list
  "POST the 'Meine Liste' action and return the listing page body. Equivalent
   to clicking the list button in the scorecard tab. Club-independent."
  [ctx club-id]
  (post ctx {:club club-id :cat "scorecard"} {"task" "list"}))

(defn parse-scorecard-list
  "Parse the scorecard list table into [{:id :date :club :course :tee :note}].
   The only field not also present in the per-card detail is the club name, so
   the id and club are what callers really need from here."
  [html]
  (->> (re-seq #"(?s)<tr data-pcco-tournament-scorecard-id=\"(\d+)\">(.*?)</tr>" html)
       (mapv (fn [[_ id block]]
               (let [cells (mapv (comp strip-tags second)
                                 (re-seq #"(?s)<td[^>]*>(.*?)</td>" block))]
                 {:id    id
                  :date  (get cells 1)
                  :club  (get cells 2)
                  :course (get cells 3)
                  :tee   (get cells 4)
                  :note  (get cells 5)})))))

(defn fetch-scorecard
  "POST the 'Laden' action for one scorecard id and return the detail page body."
  [ctx club-id id]
  (post ctx {:club club-id :cat "scorecard"} {"task" "load" "id" id "todo" ""}))

(defn parse-scorecard-detail
  "Extract the loaded scorecard's data object from the detail page's inline JS
   (the single-element rawCourseData array). Returns a keywordized map holding
   hcp, courseHcp (playing handicap), cr, slope, par, per-hole pl/vl/score, …"
  [html]
  (when-let [raw (second (re-find #"(?s)rawCourseData:\s*\$\.parseJSON\('(.*?)'\)" html))]
    (first (json/parse-string raw true))))

(def scorecard-holes 18)

(def scorecard-columns
  "CSV column order. Summary fields first, then per-hole gross/par/stroke-index."
  (into ["date" "club" "course" "tee" "gender" "holes" "handicap"
         "playing_handicap" "new_handicap" "course_rating" "slope_rating"
         "par" "gross_total" "note" "scorecard_id" "club_id"]
        (concat (for [i (range 1 (inc scorecard-holes))] (str "hole_" i "_gross"))
                (for [i (range 1 (inc scorecard-holes))] (str "hole_" i "_par"))
                (for [i (range 1 (inc scorecard-holes))] (str "hole_" i "_si")))))

(defn scorecard->row
  "Merge a list entry (for the club name) with its loaded detail `d` into a flat
   map keyed by `scorecard-columns`."
  [{:keys [club]} d]
  (let [score   (vec (:score d))
        played  (filter pos? score)
        pos->nil (fn [v] (when (and v (pos? v)) v))
        date    (:date d)]
    (merge
     {"date"             (when date (subs date 0 (min 10 (count date))))
      "club"             club
      "course"           (:name d)
      "tee"              (:color d)
      "gender"           (:gender d)
      "holes"            (:numholes d)
      "handicap"         (:hcp d)
      "playing_handicap" (:courseHcp d)
      "new_handicap"     (:newHcp d)
      "course_rating"    (:cr d)
      "slope_rating"     (:slope d)
      "par"              (:totalPar d)
      "gross_total"      (when (seq played) (reduce + played))
      "note"             (:note d)
      "scorecard_id"     (:scorecard_id d)
      "club_id"          (:club_id d)}
     (into {} (for [i (range 1 (inc scorecard-holes))]
                ;; par 0 marks a hole the course doesn't have (e.g. a 9-hole card)
                (let [par (pos->nil (get d (keyword (str "pl" i))))]
                  (merge {(str "hole_" i "_gross") (pos->nil (get score (dec i)))}
                         {(str "hole_" i "_par")   par}
                         {(str "hole_" i "_si")    (when par (get d (keyword (str "vl" i))))})))))))

(defn- csv-cell [v]
  (let [s (if (nil? v) "" (str v))]
    (if (re-find #"[\",\n\r]" s)
      (str \" (str/replace s "\"" "\"\"") \")
      s)))

(defn- csv-line [cols m] (str/join "," (map #(csv-cell (get m %)) cols)))

(defn cmd-export-scorecards [ctx club-id {:keys [out]}]
  (let [entries (parse-scorecard-list (fetch-scorecard-list ctx club-id))
        out     (or out (str "scorecards-" (java.time.LocalDate/now) ".csv"))]
    (if (empty? entries)
      (println (yellow "No scorecards found."))
      (let [_    (binding [*out* *err*]
                   (println (bold (format "Exporting %d scorecard(s)…" (count entries)))))
            rows (doall
                  (map-indexed
                   (fn [i {:keys [id date club] :as e}]
                     (binding [*out* *err*]
                       (print (dim (format "\r  [%d/%d] %s  %s" (inc i) (count entries) date club)))
                       (flush))
                     (scorecard->row e (parse-scorecard-detail (fetch-scorecard ctx club-id id))))
                   entries))]
        (binding [*out* *err*] (println))
        ;; Lead with a UTF-8 BOM so spreadsheet apps (Excel in particular)
        ;; auto-detect UTF-8 instead of mangling umlauts like Ö as Latin-1.
        (spit out (str "﻿"
                       (str/join "," scorecard-columns) "\n"
                       (str/join "\n" (map #(csv-line scorecard-columns %) rows))
                       "\n"))
        (println (green (format "Wrote %d scorecard(s) to %s" (count rows) out)))))))

;; ---------------------------------------------------------------------------
;; CLI
;; ---------------------------------------------------------------------------

(defn parse-cli [args]
  (loop [a args, opts {}, pos []]
    (if (empty? a)
      {:opts opts :args pos}
      (let [x (first a)]
        (cond
          (= x "--help")      (recur (rest a) (assoc opts :help true) pos)
          (= x "--free")      (recur (rest a) (assoc opts :free true) pos)
          (= x "--no-color")  (recur (rest a) (assoc opts :no-color true) pos)
          (str/includes? x "=")
          (let [[k v] (str/split (subs x 2) #"=" 2)]
            (recur (rest a) (assoc opts (keyword k) v) pos))
          (str/starts-with? x "--")
          (recur (drop 2 a) (assoc opts (keyword (subs x 2)) (second a)) pos)
          :else (recur (rest a) opts (conj pos x)))))))

(def usage
  (str/trim "
pccaddie.bb — PC CADDIE mobile tee-time fetcher

  ./pccaddie.bb --search '<keyword>'        List clubs matching keyword (+ ids)
  ./pccaddie.bb show-courses [--id <ID>]    List a club's courses (+ course ids)
  ./pccaddie.bb show-teetimes [options]     Show tee-time availability
  ./pccaddie.bb export-scorecards [opts]    Export every online scorecard to CSV

--id <ID> overrides the default club for that single run only (config.edn is
never modified). Edit :default-club in config.edn to change the default.

show-teetimes options:
  --date <YYYY-MM-DD|today|tomorrow|+N>     Day to query (default: today)
  --course <course-id>                      Only this course id from show-courses
                                            (e.g. ALIAS|9LO or 9LO; a name
                                            substring like 9 / 6 also works)
  --id <ID>                                 Query a club other than the default
  --free                                    Only list times with free seats
  --no-color                                Disable ANSI colors

export-scorecards options:
  --out <file>                              CSV output path
                                            (default: scorecards-<today>.csv)
  --id <ID>                                 Use a club other than the default
                                            (scorecards are club-independent, so
                                            the full list is returned regardless)
"))

(defn -main [args]
  (let [{:keys [opts args]} (parse-cli args)
        cmd    (first args)
        config (load-config)
        club   (or (some-> (:id opts) str/trim parse-long) (:default-club config))]
    (binding [*color* (and (not (:no-color opts)) (not (System/getenv "NO_COLOR")))]
      (cond
        (:help opts) (println usage)

        (:search opts)
        (cmd-search (make-session config) (:search opts))

        (= cmd "show-courses")
        (cmd-show-courses (make-session config) club)

        (= cmd "show-teetimes")
        (cmd-show-teetimes (make-session config) club
                           {:date (:date opts) :course (:course opts) :free (:free opts)})

        (= cmd "export-scorecards")
        (cmd-export-scorecards (make-session config) club {:out (:out opts)})

        :else (println usage)))))

(try
  (-main *command-line-args*)
  (catch clojure.lang.ExceptionInfo e
    (binding [*out* *err*] (println "Error:" (ex-message e)))
    (System/exit 1)))
