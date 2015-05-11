(ns cmr.common-app.api-docs
  "This namespace contains helpers for generating and returning a page documenting an application's
  APIs. API Documentation for an application consists of two parts, a welcome page and a single page with
  all of the api documentation.

  ## Welcome Page

  The welcome page is served if you hit the root URL of the application. It usually has the name of
  the application, a short description, and then a link to the documentation. Each application
  should define welcome page as <app-folder>/resources/public/index.html. See search app for an
  example.

  ## API Documentation Markdown

  API documentation is written in markdown in a single file located in <app-folder>/api_docs.md.
  You can refer to %CMR-ENDPOINT% in the documentation. It will be replaced with the public URL of
  the application when the page is served.

  ## Routing

  An application using this namespace for documentation must define routes to load the HTML pages.

  ```
  ;; In namespace definition
  (:require [cmr.common-app.api-docs :as api-docs])

  ;; In your routes
  (api-docs/docs-routes (:relative-root-url system))
  ```

  ## Generating Documentation

  API documentation can be generated with the generate function in this namespace. You should add
  an alias to generate the documentation in the project.clj

  Replace 'App Name' with the name of the application.

  ```
  :aliases {\"generate-docs\" [\"exec\" \"-ep\" \"(do (use 'cmr.common-app.api-docs) (generate \"CMR App Name\"))\"}
  ```"
  (:require [compojure.handler :as handler]
            [compojure.route :as route]
            [compojure.core :refer :all]
            [ring.util.response :as r]
            [ring.util.request :as request]
            [markdown.core :as md]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Routing Vars

(defmacro force-trailing-slash
  "Given a ring request, if the request was made against a resource path with a trailing
  slash, performs the body form (presumably returning a valid ring response).  Otherwise,
  issues a 301 Moved Permanently redirect to the request's resource path with an appended
  trailing slash."
  [req body]
  `(if (.endsWith (:uri ~req) "/")
     ~body
     (assoc (r/redirect (str (request/request-url ~req) "/")) :status 301)))

(defn docs-routes
  "Defines routes for returning API documentation."
  [relative-root-url]
  (routes
    ;; CMR Application Welcome Page
    (GET "/" req
      (force-trailing-slash req ; Without a trailing slash, the relative URLs in index.html are wrong
                            {:status 200
                             :body (slurp (io/resource "public/index.html"))}))

    ;; Static HTML resources, typically API documentation which needs endpoint URLs replaced
    (GET ["/site/:resource", :resource #".*\.html$"] {scheme :scheme headers :headers {resource :resource} :params}
      (let [cmr-root (str (name scheme)  "://" (headers "host") relative-root-url)]
        {:status 200
         :body (-> (str "public/site/" resource)
                   (io/resource)
                   (slurp)
                   (str/replace "%CMR-ENDPOINT%" cmr-root))}))

    ;; Other static resources (Javascript, CSS)
    (GET "/site/:resource" [resource]
      {:status 200
       :body (slurp (io/resource (str "public/site/" resource)))})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Documentation Generation Vars

(defn header
  "Defines the header of the generated documentation page."
  [title]
  (format
    "<!DOCTYPE html>
     <html>
     <head>
       <meta charset=\"UTF-8\" />
       <title>%s</title>
         <!--[if lt IE 9 ]>
           <script src=\"http://html5shiv.googlecode.com/svn/trunk/html5.js\"></script>
           <![endif]-->
           <link rel=\"stylesheet\" href=\"bootstrap.min.css\">
           <script src=\"jquery.min.js\"></script>
           <script src=\"bootstrap.min.js\"></script>
       </head>
       <body lang=\"en-US\">
         <div class=\"container\">
         <h1>%s</h1>"
         title title))

(def footer
  "Defines the footer of the generated documentation page."
  "</div></body></html>")

(def docs-source
  "The file containing the markdown API documentation."
  "api_docs.md")

(def docs-target
  "The file that will be generated with the API documentation."
  "resources/public/site/api_docs.html")

(defn generate
  "Generates the API documentation HTML page from the markdown source."
  [page-title]
  (println "Generating" docs-target "from" docs-source)
  (io/make-parents docs-target)
  (spit docs-target (str (header page-title) (md/md-to-html-string (slurp docs-source)) footer))
  (println "Done"))

