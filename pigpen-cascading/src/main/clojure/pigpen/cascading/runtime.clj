(ns pigpen.cascading.runtime
  (:import (org.apache.hadoop.io BytesWritable)
           (pigpen.cascading OperationUtil SingleIterationSeq)
           (cascading.tuple TupleEntryCollector Tuple)
           (java.util List))
  (:require [taoensso.nippy :refer [freeze thaw]]))

(defn hybrid->clojure [value]
  (if (instance? BytesWritable value)
    (-> value (OperationUtil/getBytes) thaw)
    value))

;; ******* Serialization ********
(defn cs-freeze [value]
  (BytesWritable. (freeze value {:skip-header? true, :legacy-mode true})))

(defn ^:private cs-freeze-with-nils [value]
  (if value (cs-freeze value)))

(defmethod pigpen.runtime/post-process [:cascading :native]
  [_ _]
  identity)

(defmethod pigpen.runtime/post-process [:cascading :frozen]
  [_ _]
  (fn [args]
    (map cs-freeze args)))

(defmethod pigpen.runtime/post-process [:cascading :frozen-with-nils]
  [_ _]
  (fn [args]
    (map cs-freeze-with-nils args)))

(defmethod pigpen.runtime/post-process [:cascading :native-key-frozen-val]
  [_ _]
  (fn [[key value]]
    [key (cs-freeze value)]))

(defn- wrap-iterator [it]
  (SingleIterationSeq/create it))

(defn emit-tuples
  "Given a seq containing the results of an operation, emit the corresponding cascading tuples."
  [seq ^TupleEntryCollector collector]
  (doseq [r seq] (.add collector (Tuple. (.toArray r)))))

(defn emit-group-buffer-tuples
  "Emit the results from a GroupBuffer."
  [f key iterators ^TupleEntryCollector collector group-all key-separate-from-value]
  (let [result (if (or group-all (not key-separate-from-value))
                 (f (map wrap-iterator iterators))
                 (f (concat [key] (map wrap-iterator iterators))))]
    (emit-tuples result collector)))

(defn emit-join-buffer-tuples
  "Emit the results from a JoinBuffer."
  [f iterator ^TupleEntryCollector collector all-args]
  (doseq [^List t (wrap-iterator iterator)]
    ; The incoming tuple contains <key1, value1, key2, value2>. Unless all-args is true, the function only
    ; cares about the values, hence the indices are 1 and 3
    (let [result (f (if all-args t
                                 [(.get t 1) (.get t 3)]))]
      (emit-tuples result collector))))

(defn emit-function-tuples
  "Emit the results from a PigPenFunction."
  [f ^List tuple ^TupleEntryCollector collector]
  (emit-tuples (f tuple) collector))

(defn get-seed-value
  "Generate the seed value for a partial aggregation."
  [{:keys [combinef]}]
  (combinef))

(defn aggregate-by-prepare
  "Apply the pre function to the arg, which results in a collection."
  [{:keys [pre]} arg]
  (pre [arg]))

(defn aggregate-by-reducef
  "Compute the result of a partial aggregation (map-side)."
  [{:keys [reducef]} args acc]
  (Tuple. (to-array [(reduce reducef acc args)])))

(defn aggregate-by-combinef
  "Compute the result of a partial aggregation (reduce-side)."
  [{:keys [combinef post]} arg acc]
  (post (combinef acc arg)))