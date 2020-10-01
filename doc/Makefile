all: doc

doc:
	mkdir -p dist/latest/
	cd ..; clojure -A:dev:codox -M doc.clj;

github: doc
	ghp-import -m "Generate documentation" -b gh-pages dist/
	git push origin gh-pages
