(ns cldwalker.schema.publish
  "Publishes tagaholic-me-index.edn as a static site of quickblog posts.

  `create` writes a quickblog post per class and property in
  tagaholic-me-index.edn, or only the ones given as args, named after its
  `:block/title`, into a site directory's posts/. Posts are rendered from the
  resources/new-post.md Selmer template. Class posts start with breadcrumbs
  of the classes they extend up to Thing. Posts have a Properties section whose
  names link to their property in either index and, for classes, an Extends
  section. Links to schema.org or other sites are external links. `rebuild`
  recreates posts and re-renders the whole site."
  (:require [babashka.fs :as fs]
            [cldwalker.schema.cli :as cli-util]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [quickblog.api :as quickblog]
            [quickblog.cli :as quickblog-cli]))

(def quickblog-opts
  "Blog options shared by the quickblog task and the tasks in this ns"
  (let [description "cldwalker's ontology based on Schema.org"]
    {:blog-title "tagaholic.me Schema"
     :blog-root "https://tagaholic.me/schema/"
     :blog-author "cldwalker"
     :about-link "https://tagaholic.me"
     ;; quickblog re-renders every page when one of these changes. This file
     ;; is added since it has these options
     :rendering-system-files ["bb.edn" "deps.edn" "src/cldwalker/schema/publish.clj"]
     ;; Not a quickblog option. Optional Bluesky handle that templates/base.html
     ;; links to as <handle>.bsky.social
     :bluesky-handle "cldwalker"
     ;; Plain text since quickblog also puts it in meta tags and feeds
     :blog-description description
     ;; Not a quickblog option. The site header's description in
     ;; templates/base.html, with Schema.org as a link
     :blog-description-html
     (str/replace description "Schema.org"
                  "<a class=\"external\" target=\"_blank\" rel=\"noopener\" href=\"https://schema.org/\">Schema.org</a>")}))

(defn quickblog-cli
  "Runs quickblog's CLI with `args` and `quickblog-opts`. quickblog.cli only
  keeps default opts it has a spec for, so the other `quickblog-opts` are
  passed as options after `args`."
  [args]
  (let [known? (set (keys (get-in (meta (the-ns 'quickblog.api)) [:org.babashka/cli :spec])))
        other-args (mapcat (fn [[k v]] [(str "--" (name k)) (str v)])
                           (remove (comp known? key) quickblog-opts))]
    (apply quickblog-cli/dispatch quickblog-opts (concat args other-args))))

(def ^:private index-files
  {:tagaholic "tagaholic-me-index.edn"
   :schema-org "schema-org-index.edn"})

(defn- read-resource-edn [resource]
  (or (some-> (io/resource resource) slurp edn/read-string)
      (cli-util/error "Unable to find" resource "on classpath")))

(def ^:private my-index
  (some-> (io/resource (:tagaholic index-files)) slurp edn/read-string))

(defn- one-line
  "Collapses whitespace in `s` so it fits on one line"
  [s]
  (str/trim (str/replace s #"\s+" " ")))

(def ^:private my-name-candidates
  "Completion candidates for task args in babashka.cli's
  `{:value :description}` form. Descriptions are one line since babashka.cli
  truncates a description at its first newline."
  (mapv (fn [[k v]]
          (let [desc (get-in v [:build/properties :logseq.property/description])]
            (cond-> {:value (symbol (name k))}
              desc (assoc :description (one-line desc)))))
        (concat (:classes my-index) (:properties my-index))))

(def ^:private new-post-template "new-post.md")

(def ^:private property-fields
  "`:build/properties` shown in a post's Properties section, in display order.
  `:name-url` links a name that isn't a property in either index."
  [{:key :logseq.property/description :name "description"
    :name-url "https://schema.org/description"}
   {:key :url :name "url" :link? true}
   {:key :schema.property/sameAs :name "sameAs" :link? true}])

(def ^:private site-url
  "Url prefix of this site's pages in tagaholic-me-index.edn. Urls with it are
  linked relative to the site so pages work locally and when deployed."
  "https://tagaholic.me/schema/")

(defn- encode-path [path] (str/replace path " " "%20"))

(defn- url-link [url]
  (if (str/starts-with? url site-url)
    (let [path (subs url (count site-url))]
      {:title path :url (encode-path path)})
    {:title url :url url :external? true}))

(defn- entry-link
  "Link titled `title` to index `entry`'s url, or nil if it has none"
  [title entry]
  (some-> (get-in entry [:build/properties :url]) url-link (assoc :title title)))

(defn- property-name-link
  "Link for a Properties section name to the property with the same name in
  tagaholic-me-index.edn or else schema-org-index.edn"
  [indexes property-name]
  (let [k (keyword property-name)]
    (some->> [:tagaholic :schema-org]
             (some #(get-in indexes [% :properties k]))
             (entry-link property-name))))

(defn- post-properties [indexes build-properties]
  (keep (fn [{:keys [key link? name name-url] :as field}]
          (when-let [value (get build-properties key)]
            (cond-> (-> field
                        (dissoc :key :link? :name-url)
                        (assoc :value value
                               :name-link (or (property-name-link indexes name)
                                              (some-> name-url url-link (assoc :title name)))))
              link? (assoc :link (url-link value)))))
        property-fields))

(defn- class-ref
  "`[index-k class-k]` of the class that `index-k`'s index refers to as
  `class-k`. schema.* namespaced classes are in schema-org-index.edn."
  [index-k class-k]
  (if (some-> (namespace class-k) (str/starts-with? "schema."))
    [:schema-org (keyword (name class-k))]
    [index-k class-k]))

(defn- class-entry [indexes [index-k class-k]]
  (or (get-in indexes [index-k :classes class-k])
      (cli-util/error "No class" class-k "found in" (index-files index-k))))

(defn- class-link [indexes [index-k class-k :as ref]]
  (let [entry (class-entry indexes ref)]
    (or (entry-link (:block/title entry) entry)
        (cli-util/error "No url found for class" class-k "in" (index-files index-k)))))

(defn- breadcrumbs
  "Links to the classes that class `class-k` extends, from the root class e.g.
  Thing down to its parent. A class with multiple parents is followed through
  its first one."
  [indexes class-k]
  (let [start [:tagaholic class-k]]
    (loop [ref start
           ancestors ()]
      (if-let [parent (first (:build/class-extends (class-entry indexes ref)))]
        (let [parent-ref (class-ref (first ref) parent)]
          (when (some #{parent-ref} (cons start ancestors))
            (cli-util/error "Class" class-k "has an extends cycle at" parent))
          (recur parent-ref (conj ancestors parent-ref)))
        (map #(class-link indexes %) ancestors)))))

(defn- ->post [indexes type [k entry]]
  (cond-> {:type type
           :title (:block/title entry)
           :file (str (:block/title entry) ".md")
           :tags [(name type)]
           ;; One line since it's a post metadata header
           :description (some-> (get-in entry [:build/properties :logseq.property/description])
                                one-line)
           :properties (post-properties indexes (:build/properties entry))
           :extends (not-empty (mapv #(class-link indexes (class-ref :tagaholic %))
                                     (:build/class-extends entry)))}
    (= :class type) (assoc :breadcrumbs (breadcrumbs indexes k))))

(defn- select-entries
  "Selects `index`'s entries named by `args`, or all entries when there are no
  `args`. Errors on names that aren't in `index`."
  [index args]
  (if (empty? args)
    (select-keys index [:classes :properties])
    (let [arg-kws (set (map keyword args))
          selected (-> index
                       (update :classes select-keys arg-kws)
                       (update :properties select-keys arg-kws)
                       (select-keys [:classes :properties]))]
      (when-let [unknown (seq (remove (set (mapcat keys (vals selected))) arg-kws))]
        (cli-util/error "These are not classes or properties in tagaholic-me-index.edn:"
                        (pr-str (vec (sort unknown)))))
      selected)))

(defn- index-posts [args]
  (let [index (or my-index (cli-util/error "Unable to find tagaholic-me-index.edn on classpath"))
        {:keys [classes properties]} (select-entries index args)
        ;; Links need every class and property, not just the selected ones
        indexes {:tagaholic index
                 :schema-org (read-resource-edn (:schema-org index-files))}]
    (concat (map #(->post indexes :class %) classes)
            (map #(->post indexes :property %) properties))))

(defn- site-dir [dir]
  (let [dir (fs/expand-home dir)]
    (when-not (fs/directory? dir) (cli-util/error "Directory" (str dir) "does not exist"))
    dir))

(defn- site-opts
  "quickblog options with every directory and file under `dir`, the same layout
  `bb quickblog` uses when run from `dir`"
  [dir]
  (let [path #(str (fs/path dir %))]
    (assoc quickblog-opts
           :posts-dir (path "posts")
           :templates-dir (path "templates")
           :assets-dir (path "assets")
           :favicon-dir (path "assets/favicon")
           :out-dir (path "public")
           :cache-dir (path ".work")
           :rendering-system-files (mapv path (:rendering-system-files quickblog-opts)))))

(defn- write-posts
  "Writes `posts` into `posts-dir` with quickblog, or only lists them when
  `pretend`, and reports how many classes and properties that is"
  [posts posts-dir pretend]
  (let [template-file (str (io/file (io/resource new-post-template)))
        counts (frequencies (map :type posts))]
    (doseq [post posts]
      (if pretend
        (println "Would create" (str (fs/path posts-dir (:file post))))
        (quickblog/new (assoc post :posts-dir posts-dir :template-file template-file))))
    (println (if pretend "Would create" "Created")
             (get counts :class 0) "classes and" (get counts :property 0) "properties in" posts-dir)))

(defn create
  "Creates a quickblog post for each class and property in
  tagaholic-me-index.edn, or only the ones in `args`. Existing posts are left
  unchanged."
  [{:keys [dir pretend args]}]
  (let [{:keys [posts-dir]} (site-opts (site-dir dir))
        {existing true new-posts false}
        (group-by #(fs/exists? (fs/path posts-dir (:file %))) (index-posts args))]
    (write-posts new-posts posts-dir pretend)
    (when (seq existing)
      (println "Skipped" (count existing) "posts that already exist"))))

(defn- post-date
  "The `Date:` of an existing post file, so a recreated post keeps it"
  [post-file]
  (when (fs/exists? post-file)
    (second (re-find #"(?m)^Date:\s*(\S+)" (slurp (fs/file post-file))))))

(defn- run-quickblog [pretend dir cmd f opts]
  (if pretend
    (println "Would run quickblog" cmd "in" (str dir))
    (f opts)))

(defn rebuild
  "Recreates posts for all or the given classes and properties in `args`,
  keeping their dates, and renders the site from scratch: runs quickblog's
  clean, deletes the posts and watch's cache, creates the posts and runs
  quickblog's render. Posts not in tagaholic-me-index.edn are left alone."
  [{:keys [dir pretend args]}]
  (let [dir (site-dir dir)
        {:keys [posts-dir] :as opts} (site-opts dir)
        ;; quickblog watch's cache, which clean doesn't remove
        watch-cache-dir (fs/path dir ".work" "dev")
        posts (map (fn [post]
                     (if-let [date (post-date (fs/path posts-dir (:file post)))]
                       (assoc post :date date)
                       post))
                   (index-posts args))
        existing-files (filter fs/exists? (map #(fs/path posts-dir (:file %)) posts))]
    (run-quickblog pretend dir "clean" quickblog/clean opts)
    (if pretend
      (println "Would delete" (count existing-files) "posts and" (str watch-cache-dir))
      (do (run! fs/delete existing-files)
          (fs/delete-tree watch-cache-dir)))
    (write-posts posts posts-dir pretend)
    (run-quickblog pretend dir "render" quickblog/render opts)))

(def task-cli
  "babashka.cli options for the schema:create and schema:rebuild tasks.
  Referenced as `:cli` in bb.edn."
  {:spec {:dir {:alias :d
                :default "."
                :desc "quickblog site directory whose posts/ is written to"}
          :pretend {:alias :n
                    :coerce :boolean
                    :desc "Only report what would be done"}
          :args {:coerce [:symbol]
                 :complete my-name-candidates
                 :desc "tagaholic-me-index.edn classes or properties. Defaults to all"}}
   :args->opts (repeat :args)})
