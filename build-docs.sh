#!/bin/sh
VERSION="latest"

(cd doc; make)

rm -rf /tmp/promesa-doc/
mkdir -p /tmp/promesa-doc/
mv doc/*.html /tmp/promesa-doc/

git checkout gh-pages;

rm -rf ./$VERSION
mv /tmp/promesa-doc/ ./$VERSION

git add --all ./$VERSION
git commit -a -m "Update ${VERSION} doc"
