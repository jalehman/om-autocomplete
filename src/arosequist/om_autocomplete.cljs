(ns arosequist.om-autocomplete
  (:require-macros [cljs.core.async.macros :refer [go alt!]])
  (:require [cljs.core.async :refer [chan close! put! take! timeout]]
            [om.core :as om :include-macros true]))

(defn- handle-highlight
  [owner idx]
  (let [idx (min idx (count (om/get-state owner :suggestions)))
        idx (max idx -1)]
    (om/set-state! owner :highlighted-index idx)))

(defn- handle-select
  [owner result-ch idx]
  (let [suggestions (om/get-state owner :suggestions)
        item (nth suggestions idx)]
    (put! result-ch [idx item])))

(defn autocomplete [cursor owner {:keys [result-ch
                                         suggestions-fn
                                         container-view container-view-opts
                                         input-view input-view-opts
                                         results-view results-view-opts]}]
  (reify
    om/IInitState
    (init-state [_]
      {:focus-ch (chan) :value-ch (chan) :highlight-ch (chan) :select-ch (chan)})

    om/IWillMount
    (will-mount [_]
      (let [{:keys [focus-ch value-ch highlight-ch select-ch]} (om/get-state owner)]
        (go
         (loop []
           (alt!
            focus-ch ([v _] (om/set-state! owner :focused? v))
            value-ch ([v _] (om/set-state! owner :value v))
            highlight-ch ([v c] (handle-highlight owner v))
            select-ch ([v _] (handle-select owner result-ch v)))
           (recur)))))

    om/IDidUpdate
    (did-update [_ _ old]
      (let [old-value (:value old)
            new-value (om/get-state owner :value)]
        (when (not= old-value new-value)
          (om/update-state! owner
                            (fn [state]
                              (let [old-suggestions-ch (:suggestions-ch state)
                                    old-cancel-ch (:cancel-suggestions-ch state)
                                    new-suggestions-ch (chan)
                                    new-cancel-ch (chan)]
                                (when old-suggestions-ch (close! old-suggestions-ch))
                                (when old-cancel-ch (close! old-cancel-ch))
                                (take! new-suggestions-ch
                                       (fn [suggestions]
                                         (om/update-state! owner
                                                      (fn [s]
                                                        (assoc s
                                                          :suggestions suggestions
                                                          :loading? false)))))
                                (assoc state
                                  :suggestions-ch new-suggestions-ch
                                  :cancel-suggestions-ch new-cancel-ch
                                  :loading? true))))
          (suggestions-fn
            new-value
            (om/get-state owner :suggestions-ch)
            (om/get-state owner :cancel-suggestions-ch)))))

    om/IRenderState
    (render-state [_ {:keys [focus-ch value-ch highlight-ch select-ch value highlighted-index loading? focused? suggestions]}]
      (om/build container-view cursor
                {:state
                 {:input-component
                  (om/build input-view cursor
                            {:init-state {:focus-ch focus-ch
                                          :value-ch value-ch
                                          :highlight-ch highlight-ch
                                          :select-ch select-ch}
                             :state {:value value
                                     :highlighted-index highlighted-index}
                             :opts input-view-opts})
                  :results-component
                  (om/build results-view cursor
                            {:init-state {:highlight-ch highlight-ch
                                          :select-ch select-ch}
                             :state {:value value
                                     :loading? loading?
                                     :focused? focused?
                                     :suggestions suggestions
                                     :highlighted-index highlighted-index}
                             :opts results-view-opts})}
                 :opts container-view-opts}))))
