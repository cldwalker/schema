(ns cldwalker.schema.publish
  "Publishes tagaholic-me-index.edn as a static site of quickblog posts.

  `create` writes a quickblog post per class and property in
  tagaholic-me-index.edn, or only the ones given as args, named after its
  `:block/title`, into a site directory's posts/. Posts are rendered from the
  resources/cldwalker/schema/post.md Selmer template and have a Properties
  section and, for classes, an Extends section that links to local pages or
  schema.org. `rebuild` recreates posts and re-renders the whole site."
  (:require [babashka.fs :as fs]
            [cldwalker.schema.cli :as cli-util]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [quickblog.api :as quickblog]))

(def quickblog-opts
  "Blog options shared by the quickblog task and the tasks in this ns"
  {:blog-title "tagaholic.me Schema"
   :blog-root "https://tagaholic.me/schema/"
   :blog-author "cldwalker"
   :about-link "https://tagaholic.me"
   ;; Plain text since quickblog also puts it in meta tags and feeds.
   ;; templates/base.html has the site header's version with a Schema.org link
   :blog-description "A website that describes cldwalker's ontology based on Schema.org"})

(defn- read-resource-edn [resource]
  (or (some-> (io/resource resource) slurp edn/read-string)
      (cli-util/error "Unable to find" resource "on classpath")))

(def ^:private my-index
  (some-> (io/resource "tagaholic-me-index.edn") slurp edn/read-string))

(def ^:private my-name-candidates
  "Completion candidates for task args in babashka.cli's
  `{:value :description}` form. Whitespace in descriptions is collapsed since
  babashka.cli truncates a description at its first newline."
  (mapv (fn [[k v]]
          (let [desc (get-in v [:build/properties :logseq.property/description])]
            (cond-> {:value (symbol (name k))}
              desc (assoc :description (str/trim (str/replace desc #"\s+" " "))))))
        (concat (:classes my-index) (:properties my-index))))

(def ^:private new-post-template "new-post.md")

(def ^:private property-fields
  "`:build/properties` shown in a post's Properties section, in display order"
  [{:key :logseq.property/description :name "description"}
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

(defn- post-properties [build-properties]
  (keep (fn [{:keys [key link?] :as field}]
          (when-let [value (get build-properties key)]
            (cond-> (-> field (dissoc :key :link?) (assoc :value value))
              link? (assoc :link (url-link value)))))
        property-fields))

(defn- extends-link [classes schema-org-classes class-k]
  (if (some-> (namespace class-k) (str/starts-with? "schema."))
    {:title (name class-k)
     :external? true
     :url (or (get-in schema-org-classes [(keyword (name class-k)) :build/properties :url])
              (cli-util/error "No schema-org-index.edn url found for" class-k))}
    (let [title (get-in classes [class-k :block/title] (name class-k))]
      ;; Same name quickblog gives a post's page
      {:title title :url (encode-path (str title ".html"))})))

(defn- ->post [classes schema-org-classes type entry]
  {:type type
   :title (:block/title entry)
   :file (str (:block/title entry) ".md")
   :tags [(name type)]
   :properties (post-properties (:build/properties entry))
   :extends (not-empty (mapv (partial extends-link classes schema-org-classes)
                             (:build/class-extends entry)))})

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
        schema-org-classes (:classes (read-resource-edn "schema-org-index.edn"))
        ;; Extends links need every class's title, not just the selected ones
        ->post' (partial ->post (:classes index) schema-org-classes)]
    (concat (map #(->post' :class %) (vals classes))
            (map #(->post' :property %) (vals properties)))))

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
           :rendering-system-files [(path "bb.edn") (path "deps.edn")])))

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
