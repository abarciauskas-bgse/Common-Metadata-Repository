(ns cmr.umm-spec.url
  "URL utilities for UMM spec"
  (:import java.net.URL
           java.net.MalformedURLException))

(defn url
  "Attempts to return a URL from the provided value. Returns nil if the value is not a URL."
  [x]
  (if (isa? x URL)
    x
    (try
      (URL. x)
      (catch MalformedURLException _
        nil))))

(defn protocol
  "Attempts to return a protocol from the provided value. Returns http if the protocol cannot be
  determined."
  [x]
  (or
   (some-> x url .getProtocol)
   "http"))
