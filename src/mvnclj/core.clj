(ns mvnclj.core
  (:require [cemerick.pomegranate :as dep]
            [cemerick.pomegranate.aether :as aether]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (org.apache.maven.model.io.xpp3 MavenXpp3Reader)
           (org.apache.maven.model Model Dependency Plugin Repository)
           (org.codehaus.plexus.util.xml Xpp3Dom)
           (java.util Map$Entry)
           (java.util.jar  Manifest JarOutputStream JarEntry)
           (java.io File FileOutputStream ByteArrayInputStream BufferedOutputStream FileInputStream)
           (javax.tools ToolProvider)))

(defn ^Model create-model
  "Given a pom file returns the maven Model object."
  [pom]
  (with-open [reader (io/reader pom)]
    (.read (MavenXpp3Reader.) reader)))

(defn extract-dependency
  "Given a maven Dependency object creates a vector
  [groupId/artifactId version :scope scope :extension type :classifier
  classifier]."
  [^Dependency dep]
  (let [groupId (.getGroupId dep)
        artifactId (.getArtifactId dep)
        version (.getVersion dep)
        type (.getType dep)
        classifier (.getClassifier dep)
        scope (.getScope dep)]
    [(symbol (str groupId "/" artifactId))
     version :scope scope :extension type :classifier classifier]))

(defn extract-dependencies
  "Returns a list of all dependencies listed in the model. Each
  dependency is represented using a vector [groupId/artifactId
  version :scope scope :extension type :classifier classifier]"
  [^Model model]
  (let [deps (seq (.getDependencies model))]
    (map extract-dependency deps)))

(defn extract-properties
  "Returns a map of all defined properties in the model."
  [^Model model]
  (loop [entries (seq (.getProperties model))
         res {}]
    (if-let [^Map$Entry entry (first entries)]
      (let [key (.getKey entry)
            value (.getValue entry)]
        (recur (rest entries) (assoc res key value)))
      res)))

(defn extract-repositories
  "Returns a map of all defined repositories in the model."
  [^Model model]
  (loop [repos (seq (.getRepositories model))
         res {}]
    (if-let [^Repository repo (first repos)]
      (recur (rest repos) (assoc res (.getName repo) (.getUrl repo)))
      res)))

(defn extract-coordinate
  "Returns the coordinates of the project defined by the model
  represented as a vector [groupId/artifactId version :packaging
  scope]."
  [^Model model]
  (let [groupId (.getGroupId model)
        artifactId (.getArtifactId model)
        version (.getVersion model)
        type (.getPackaging model)]
    [(symbol (str groupId "/" artifactId)) version :packaging (or type "jar")]))

(defn extract-parent
  "Returns the coordinate of the parent pom if defined, nil
  otherwise."
  [^Model model]
  (if-let [parent (.getParent model)]
    [(symbol (str (.getGroupId parent) "/" (.getArtifactId parent))) (.getVersion parent)]
    nil))

(defn extract-modules
  "Returns the list of modules defined in a the model (if this model
  represents a parent pom), nil if there are no modules."
  [^Model model]
  (if-let [modules (.getModules model)]
    (if (> (.size modules) 0)
      (seq modules)
      nil)
    nil))

(defn extract-build-plugins
  "Returns a list of Plugins defined in the build section of the pom."
  [^Model model]
  (concat
   (some->> model
            (.getBuild)
            (.getPlugins))
   (some->> model
            (.getBuild)
            (.getPluginManagement)
            (.getPlugins))))

(defn is-jar-plugin?
  "Returns true if the given plugin is org.apache.maven.plugins/maven-jar-plugin."
  [^Plugin plugin]
  (and (= (.getGroupId plugin) "org.apache.maven.plugins")
       (= (.getArtifactId plugin) "maven-jar-plugin")))

(defn is-compiler-plugin?
  "Returns true if the given plugin is org.apache.maven.plugins/maven-compiler-plugin."
  [^Plugin plugin]
  (and (= (.getGroupId plugin) "org.apache.maven.plugins")
       (= (.getArtifactId plugin) "maven-compiler-plugin")))

(defn extract-plugin
  "Returns the plugin that matches the given filter."
  [^Model model plugin-filter]
  (if-let [plugins (filter plugin-filter (extract-build-plugins model))]
    (first plugins)
    nil))

(defn extract-jar-plugin
  "Returns the maven-jar-plugin if defined in the build section of the model."
  [^Model model]
  (extract-plugin model is-jar-plugin?))

(defn extract-compiler-plugin
  "Returns the maven-compiler-plugin if defined in the build section of the model."
  [^Model model]
  (extract-plugin model is-compiler-plugin?))

(defn dom->clj
  "Converts a Xpp3Dom object to a vector representing the structure of the dom."
  [^Xpp3Dom dom]
  (if (nil? dom)
    nil
    (let [children (.getChildren dom)
          name (keyword (.getName dom))]
      (if (= (alength children) 0)
        [name (.getValue dom)]
        (reduce conj [name] (map dom->clj children))))))

(defn plugin-configuration
  "Returns the vector representing the configuration section extracted
  from the model by the extractor fn."
  [^Model model extractor]
  (if-let [^Plugin plugin (extractor model)]
    (dom->clj (.getConfiguration plugin))))

(defn extract-manifest
  "Returns the entries for the MANIFEST.MF defined in the configuration section of the maven-jar-plugin."
  [^Model model]
  (if-let [entries (->> (plugin-configuration model extract-jar-plugin)
                        (rest)
                        (filter #(= (first %)))
                        (first)
                        (rest)
                        (filter #(= (first %) :manifestEntries))
                        (first)
                        (rest))]
    (loop [kvs (flatten entries)
           res {}]
      (if (empty? kvs)
        res
        (recur (rest (rest kvs))
               (assoc res (first kvs) (second kvs)))))))

(defn extract-compiler-opts
  "Returns a vector with the options to javac."
  [^Model model]
  (if-let [entries (plugin-configuration model extract-compiler-plugin)]
    (let [config (:configuration entries)
          source (:source config "1.6")
          target (:target config "1.6")
          warns (:showWarnings config "false")]
      (if (= warns "true")
        (conj [] "-source" source "-target" target "-Xlint:all")
        (conj [] "-source" source "-target" target "-Xlint:none")))
    []))


(defn base-project
  "Creates a map with the basic info extracted from the given pom. The
  keys are:
  :coordinate The coordinate of the pom.
  :parent The coordinate of the parent pom.
  :modules The modules defined in the pom.
  :manifest The manifest entries defined in the pom.
  :dependencies The dependencies defined in the pom.
  :properties The properties defined in the pom."
  ([pom]
     (base-project pom {}))
  ([pom repos]
     (let [file (io/file pom)
           ^Model model (create-model file)
           coordinate (extract-coordinate model)
           properties (extract-properties model)
           parent (extract-parent model)
           modules (extract-modules model)
           dependencies (extract-dependencies model)
           manifest (extract-manifest model)
           repositories (extract-repositories model)
           compiler-opts (extract-compiler-opts model)]
       {:coordinate coordinate :source file
        :parent parent :modules modules
        :manifest manifest
        :repositories (merge repositories repos)
        :properties properties :dependencies dependencies
        :compiler-opts compiler-opts})))

(defn parent-path
  "Returns the default path for the parent pom."
  [project]
  (str (.getParent ^File (:source project)) "/../pom.xml"))

(defn module-path
  "Returns the default path for a module pom."
  [base module]
  (str (.getParent ^File (:source base)) "/" module "/pom.xml"))

(defn expand-property
  "Given ${foo-bar} returns the value of the property foo-bar. If the property is not defined returns the given value."
  [version properties]
  (let [match (re-matches #"^\$\{(.*)\}$" version)]
    (if (nil? match)
      version
      (get properties (second match) version))))

(defn expand-properties
  "Interpolates the values of properties for versions in dependencies
   expressed as <version>${foo-bar}</version>."
  [deps props]
  (map #(assoc % 1 (expand-property (second %) props)) deps))

(defn expand-module-properties
  "Interpolates the values of properties defined in the pom and in
  parent pom for versions in dependencies expressed as
  <version>${foo-bar}</version>."
  [base module]
  (let [properties (merge (:properties base)
                          (:properties module))]
    (expand-properties (:dependencies module) properties)))

(defn project-parent
  "Returns the basic project for a parent pom."
  [base]
  (let [modules (map #(base-project (module-path base %)) (:modules base))
        dependencies (distinct (concat (map #(expand-module-properties base %) modules)))]
    (assoc base :modules modules :dependencies dependencies)))

(defn resolve-dependencies
  "Resolves pom dependencies using aether."
  [repos deps]
  (let [resolved (aether/resolve-dependencies :coordinates deps
                                              :repositories repos)]
    (map first resolved)))

(defn project-child
  "Returns the basic info for a child pom."
  ([base]
     (project-child base (base-project (parent-path base))))
  ([base parent]
     (let [properties (merge (:properties base)
                             (:properties parent))
           dependencies (distinct (concat (expand-properties (:dependencies base) properties)
                                          (expand-properties (:dependencies parent) properties)))
           repositories (merge (:repositories parent) (:repositories base))
           project (assoc base
                     :properties properties
                     :dependencies dependencies
                     :repositories repositories)]
       (if (empty? (:compiler-opts project))
         (assoc project :compiler-opts (:compiler-opts parent))
         project))))

(defn project-plain
  "Returns the basic project for a plain pom (neither parent nor child) ."
  [base]
  (assoc base :dependencies (expand-properties (:dependencies base) (:properties base))))

(defn create-project
  "Creates the basic project for the given pom."
  [pom repositories]
  (let [base (base-project pom repositories)]
    (if (:modules base)
      (project-parent base)
      (if (:parent base)
        (project-child base)
        (project-plain base)))))

(defn project
  "Returns the project for the given pom with dependencies resolved."
  [pom & {:keys [repositories] :or {repositories {}}}]
  (let [project (create-project pom repositories)]
    (if-not (:modules project)
      (assoc project :resolved (resolve-dependencies (:repositories project) (:dependencies project)))
      project)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Compile
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^File source-directory
  "Returns a java.io.File representing the directory where the java
  sources live."
  [project]
  (io/file (str (.getParent ^File (:source project)) "/src/main/java")))

(defn ^File resources-directory
  "Returns a java.io.File representing the directory where
  the resources live."
  [project]
  (io/file (str (.getParent ^File (:source project)) "/src/main/resources")))

(defn ^File target-directory
  "Returns the java.io.File where the compilation runs."
  [project]
  (File. ^String (.getParent ^File (:source project)) "/target"))

(defn ^File output-directory
  "Returns the java.io.File where the java .class files are put by the
  compiler."
  [project]
  (File. ^String (.getParent ^File (:source project)) "/target/classes"))

(defn prepare-output
  "Creates the output directory if does not exists."
  [project]
  (let [^File output (output-directory project)]
    (do
      (if-not (.exists output)
        (.mkdirs output))
      output)))

(defn create-sources-lst
  "Returns a list of source files that have not been modified after
  its corresponding .class file."
  [^File source ^File output]
  (let [output-path (.getAbsolutePath output)
        source-len (.length (.getAbsolutePath source))]
    (loop [sources (filter #(.endsWith ^String (.getPath ^File %) ".java") (file-seq source))
           res ()]
      (if-let [^File src (first sources)]
        (let [^File expected (File. output (.replaceFirst (.substring (.getAbsolutePath src) source-len) "^(.*).java$" "$1.class"))]
          (recur (rest sources) (if (> (.lastModified src) (.lastModified expected))
                                  (conj res src) res)))
        res))))

(defn create-classpath
  "Creates a classpath string dep1:dep2:...:depN."
  [project]
  (let [dependencies (:resolved project)]
    (str (str/join ":" (map #(.getAbsolutePath ^File (:file (meta %))) dependencies))
         ":" (.getAbsolutePath (output-directory project)))))

(defn resource-destination
  "Returns the file in the target directory where a source resource
  file should be put."
  [^File file ^File source ^File output]
  (let [prefix-len (.length (.getAbsolutePath source))
        base (.substring ^String (.getAbsolutePath file) prefix-len)
        target (File. (str (.getAbsolutePath output) "/" base))
        target-dir (.getParentFile target)]
    (do
      (.mkdirs target-dir)
      target)))

(defn copy-resources
  "Copies every resource in the source dir to the target dir."
  [project]
  (let [source (resources-directory project)
        output (prepare-output project)
        files (filter #(.isFile ^File %) (file-seq source))]
    (dorun (map #(io/copy % (resource-destination % source output)) files))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn compile-sources
  [project]
  (let [^File source (source-directory project)
        ^File output (prepare-output project)
        sources (create-sources-lst source output)]
    (if-not (empty? sources)
      (let [options (into (:compiler-opts project) ["-cp" (create-classpath project) "-d" (.getAbsolutePath ^File output)])
            javac (ToolProvider/getSystemJavaCompiler)
            filemgr (.getStandardFileManager javac nil nil nil)
            units (.getJavaFileObjectsFromFiles filemgr sources)]
        (do
          (copy-resources project)
          (.call (.getTask javac nil filemgr nil options nil units))))
      true)))

;; Parts of the creation of the manifest were borrowed from lein.
(def ^:private default-manifest
  {"Created-By" "MvnClj 0.1.0"
   "Built-By" (System/getProperty "user.name")
   "Build-Jdk" (System/getProperty "java.version")})

(defn manifest-entry
  "Creates a Manifest entry with the given key and value."
  [[k v]]
  (->> (str (name k) ": " v)
       (partition-all 70)
       (map (partial apply str))
       (str/join "\n ")
       (format "%s\n")))

(defn make-manifest
  "Creates the Manifest object to write the jar file."
  [project]
  (->> (merge default-manifest (:manifest project))
       (map manifest-entry)
       (cons  "Manifest-Version: 1.0\n")
       (str/join "")
       .getBytes
       ByteArrayInputStream.
       Manifest.))

(defn jar-entry-name
  "Given an absolute file path returns the relative file path to be
  stored in the jar file."
  [project ^File entry]
  (let [prefix-len (inc (.length (.getAbsolutePath (output-directory project))))
        base (.substring ^String (.getAbsolutePath entry) prefix-len)]
    base))

(defn write-jar-entry
  "Creates a new entry into the jar output stream."
  [project ^JarOutputStream out ^File entry]
  (let [output-path (.getAbsolutePath (output-directory project))
        entry-path (.getAbsolutePath entry)]
    (if-not (= output-path entry-path)
      (let [prefix-len (inc (.length output-path))
            entry-name (.substring ^String (.getAbsolutePath entry) prefix-len)]
        (if (.isDirectory entry)
          (.putNextEntry out (JarEntry. (str entry-name "/")))
          (do (.putNextEntry out (JarEntry. entry-name))
              (with-open [in (FileInputStream. entry)]
                (io/copy in out))))))))

(defn ^File output-file
  "Returns a java.io.File representing the path where the jar will be
  written."
  [project ^File dir]
  (File. dir (str (second (re-matches #"^[^/]+/(.*)$" (str (first (:coordinate project))))) "-" (second (:coordinate project)) ".jar")))

(defn package-project
  "Creates the final jar file."
  [project]
  (let [output-dir (output-directory project)
        files (file-seq output-dir)
        out-file (output-file project (target-directory project))]
    (with-open [out (-> out-file
                        (FileOutputStream.)
                        (BufferedOutputStream.)
                        (JarOutputStream. (make-manifest project)))]
      (dorun (map (partial write-jar-entry project out) files)))
    true))

(defn- remove-recursively
  "Removes a directory recursively. This directory should not contain
  symlinks which are not handled correctly."
  [^File file]
  (do
    (if (.isDirectory file)
      (doseq [child (.listFiles file)]
        (remove-recursively child)))
    (io/delete-file file)))

(defn clean
  "Removes the target directory."
  [project]
  (let [^File target (target-directory project)]
    (if (.exists target)
      (remove-recursively target))))

(defn install-project
  "Installs the jar file into the local repository (~/.m2) using aether."
  [project]
  (aether/install :coordinates (:coordinate project)
                  :jar-file (output-file project (target-directory project))
                  :pom-file (:source project)))

(defn package
  "Does copy-resources, compile-sources & package-project"
  [project]
  (if (compile-sources project)
    (package-project project)
    false))

(defn install
  "Does copy-resources, compile-sources, package-project & install-project"
  [project]
  (if (package project)
    (install-project project)))
