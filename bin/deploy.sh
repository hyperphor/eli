#!/bin/sh
# Heroku deploy, same pattern as pici/okc and hyperphor/nlq-aact.
# One-time setup: copy bin/heroku-jvm-application-deployer-4.0.12.jar in from
# either of those repos (not checked in here yet -- deploy is past M1's
# scope, see design/design.md §10).
set -e
lein do clean, uberjar
HEROKU_API_KEY=$(heroku auth:token) java -jar bin/heroku-jvm-application-deployer-4.0.12.jar --app=eli target/uberjar/eli-standalone.jar
