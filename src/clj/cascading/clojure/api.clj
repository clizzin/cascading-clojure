(ns cascading.clojure.api
  (:refer-clojure :exclude (count first last filter mapcat map group-by))
  (:use [clojure.contrib.seq-utils :only (find-first indexed)])
  (:use [cascading.clojure.io :only (decode-json encode-json)])
  (:use cascading.clojure.parse)
  (:import (cascading.tuple Tuple TupleEntry Fields)
           (cascading.scheme TextLine)
           (cascading.flow Flow FlowConnector)
           (cascading.operation Identity)
           (cascading.operation.filter Limit)
           (cascading.operation.aggregator First Last Count)
           (cascading.pipe Pipe Each Every GroupBy CoGroup)
           (cascading.pipe.cogroup InnerJoin OuterJoin
                                   LeftJoin RightJoin MixedJoin)
           (cascading.scheme Scheme)
           (cascading.tap Hfs Lfs Tap)
           (cascading.cascade Cascade CascadeConnector)
           (org.apache.hadoop.io Text)
           (org.apache.hadoop.mapred TextInputFormat TextOutputFormat
                                     OutputCollector JobConf)
           (java.util Properties Map UUID)
           (cascading.clojure ClojureFilter ClojureMapcat ClojureMap
                              ClojureAggregator ClojureBuffer Util)
           (clojure.lang Var)
           (java.io File)
           (java.lang RuntimeException)))

(defn pipes-array
  [pipes]
  (into-array Pipe pipes))

(defn as-pipes
  [pipe-or-pipes]
  (pipes-array
    (if (instance? Pipe pipe-or-pipes)
      [pipe-or-pipes]
      pipe-or-pipes)))

(defn- uuid []
  (str (UUID/randomUUID)))

(defn pipe
  "Returns a new Pipe with a unique random name or a given name, or returns a
   given pipe with a given new name."
  ([]
   (Pipe. (uuid)))
  ([#^String name]
   (Pipe. name))
  ([previous name]
   (Pipe. name previous)))

(defn ensure-pipe
  "Ensures that the argument is a pipe and returns it, with an appropriate
   type hint."
   {:tag Pipe}
   [pipe]
   (if (instance? Pipe pipe)
     pipe
     (throw (IllegalArgumentException.
              (str "Expected a pipe but got: " pipe)))))

(defn filter [previous & args]
  (let [previous (ensure-pipe previous)
        opts     (parse-args args)]
    (Each. previous #^Fields (:< opts)
      (ClojureFilter. (:fn-spec opts)))))

(defn mapcat [previous & args]
  (let [previous (ensure-pipe previous)
        opts (parse-args args)]
    (Each. previous #^Fields (:< opts)
      (ClojureMapcat. (:fn> opts) (:fn-spec opts)) #^Fields (:> opts))))

(defn map [previous & args]
  (let [previous (ensure-pipe previous)
        opts     (parse-args args)]
    (Each. previous #^Fields (:< opts)
      (ClojureMap. (:fn> opts) (:fn-spec opts)) #^Fields (:> opts))))

(defn extract [previous & args]
  "A map operation that extracts a new field, thus returning Fields/ALL."
  (let [previous (ensure-pipe previous)
        opts     (parse-args args)]
    (Each. previous #^Fields (:< opts)
      (ClojureMap. (:fn> opts) (:fn-spec opts)) Fields/ALL)))

(defn agg [f init]
  "A combinator that takes a fn and an init value and returns a reduce aggregator."
  (fn ([] init)
    ([x] [x])
    ([x y] (f x y))))

(defn aggregate [previous & args]
  (let [previous (ensure-pipe previous)
        opts     (parse-args args)]
    (Every. previous #^Fields (:< opts)
      (ClojureAggregator. (:fn> opts) (:fn-spec opts)) #^Fields (:> opts))))

(defn buffer [previous & args]
  (let [previous (ensure-pipe previous)
        opts     (parse-args args)]
    (Every. previous #^Fields (:< opts)
      (ClojureBuffer. (:fn> opts) (:fn-spec opts)) #^Fields (:> opts))))

(defn group-by
  ([previous group-fields]
     (GroupBy. (as-pipes previous) (fields group-fields)))
  ([previous group-fields sort-fields]
     (GroupBy. (as-pipes previous) (fields group-fields) (fields sort-fields)))
  ([previous group-fields sort-fields reverse-order]
     (GroupBy. (as-pipes previous) (fields group-fields) (fields sort-fields) reverse-order)))

(defn first
  ([#^Pipe previous]
   (Every. previous (First.)))
  ([#^Pipe previous in-fields]
   (Every. previous (fields in-fields) (First.))))

(defn last
  ([#^Pipe previous]
   (Every. previous (Last.)))
  ([#^Pipe previous in-fields]
   (Every. previous (fields in-fields) (Last.))))

(defn count [#^Pipe previous #^String count-fields]
  (Every. previous
    (Count. (fields count-fields))))

(defn- fields-array
  [fields-seq]
  (into-array Fields (clojure.core/map fields fields-seq)))

(defn co-group
  [pipes-seq fields-seq declared-fields joiner]
  (CoGroup.
    (pipes-array pipes-seq)
    (fields-array fields-seq)
    (fields declared-fields)
    joiner))

; TODO: create join abstractions. http://en.wikipedia.org/wiki/Join_(SQL)

; "join and drop" is called a natural join - inner join, followed by select to
; remove duplicate join keys.

; another kind of join and dop is to drop all the join keys - for example, when
; you have extracted a specil join key jsut for grouping, you typicly want to get
; rid of it after the group operation.

; another kind of "join and drop" is an outer-join followed by dropping the nils

(defn inner-join
  [pipes-seq fields-seq declared-fields]
  (co-group pipes-seq fields-seq declared-fields (InnerJoin.)))

(defn outer-join
  [pipes-seq fields-seq declared-fields]
  (co-group pipes-seq fields-seq declared-fields (OuterJoin.)))

(defn left-join
  [pipes-seq fields-seq declared-fields]
  (co-group pipes-seq fields-seq declared-fields (LeftJoin.)))

(defn right-join
  [pipes-seq fields-seq declared-fields]
  (co-group pipes-seq fields-seq declared-fields (RightJoin.)))

(defn mixed-join
  [pipes-seq fields-seq declared-fields inner-bools]
  (co-group pipes-seq fields-seq declared-fields
      (MixedJoin. (into-array Boolean inner-bools))))

(defn join-into
  "outer-joins all pipes into the leftmost pipe"
  [pipes-seq fields-seq declared-fields]
  (co-group pipes-seq fields-seq declared-fields
      (MixedJoin.
       (boolean-array (cons true
             (repeat (- (clojure.core/count pipes-seq)
            1) false))))))

(defn select [#^Pipe previous keep-fields]
  (Each. previous (fields keep-fields) (Identity.)))

(defn text-line
 ([]
  (TextLine. Fields/FIRST))
 ([field-names]
  (TextLine. (fields field-names) (fields field-names))))

(defn- serialized-line [deserialize serialize field-name]
  (let [scheme-fields (fields (or field-name Fields/FIRST))]
    (proxy [Scheme] [scheme-fields scheme-fields]
      (sourceInit [tap #^JobConf conf]
        (.setInputFormat conf TextInputFormat))
      (sinkInit [tap #^JobConf conf]
        (doto conf
          (.setOutputKeyClass Text)
          (.setOutputValueClass Text)
          (.setOutputFormat TextOutputFormat)))
      (source [_ #^Text ser-text]
        (let [ser-str (.toString ser-text)]
          (Util/coerceToTuple [(deserialize ser-str)])))
      (sink [#^TupleEntry tuple-entry #^OutputCollector output-collector]
        (let [elem (Util/coerceFromTupleElem (.get tuple-entry 0))
              ser-str (serialize elem)]
          (.collect output-collector nil (Tuple. #^String ser-str)))))))

(defn clojure-line [& [field-name]]
  (serialized-line read-string pr-str field-name))

(defn json-line [& [field-name]]
  (serialized-line decode-json encode-json field-name))

(defn path
  {:tag String}
  [x]
  (if (string? x) x (.getAbsolutePath #^File x)))

(defn hfs-tap [#^Scheme scheme path-or-file]
  (Hfs. scheme (path path-or-file)))

(defn lfs-tap [#^Scheme scheme path-or-file]
  (Lfs. scheme (path path-or-file)))

(defn flow
  ([#^Map source-map #^Tap sink #^Pipe pipe]
   (flow nil {} source-map sink pipe))
  ([jar-path config #^Map source-map #^Tap sink #^Pipe pipe]
   (let [props (Properties.)]
     (when jar-path
       (FlowConnector/setApplicationJarPath props jar-path))
     (.setProperty props "mapred.used.genericoptionsparser" "true")
     (.setProperty props "cascading.flow.job.pollinginterval" "200")
     (.setProperty props "cascading.serialization.tokens"
                         "130=cascading.clojure.ClojureWrapper")
     (doseq [[k v] config]
       (.setProperty props k v))
     (let [flow-connector (FlowConnector. props)]
       (try
         (.connect flow-connector (uuid) source-map sink pipe)
         (catch cascading.flow.PlannerException e
           (.writeDOT e "exception.dot")
           (throw (RuntimeException.
             "see exception.dot to visualize your broken plan." e))))))))

(defn write-dot [#^Flow flow #^String path]
  (.writeDOT flow path))

(defn exec [#^Flow flow]
  (doto flow .start .complete))

(defn cascade  [& args]
  (let [casc (CascadeConnector.)]
    (.connect casc (into-array args))))

(defn exec-cascade [#^Cascade c]
  (.run c))
