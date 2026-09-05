#!/bin/sh
# Sets up Leiningen `checkouts/` symlinks for way/ellum, developed alongside
# eli, so editing their source is picked up live (no `lein install` round
# trip per edit). See pici/okc's identical script/rationale.
#
# Assumes the sibling repos live at ../../hyperphor/<name> relative to this
# repo -- adjust if your checkout layout differs.

set -e
cd "$(dirname "$0")/.."
mkdir -p checkouts
ln -sf /opt/mt/repos/hyperphor/way checkouts/way
ln -sf /opt/mt/repos/hyperphor/ellum checkouts/ellum
echo "Linked checkouts/way -> /opt/mt/repos/hyperphor/way"
echo "Linked checkouts/ellum -> /opt/mt/repos/hyperphor/ellum"
echo "(Run 'lein install' in each at least once so its own transitive deps"
echo " resolve normally; checkouts only live-overrides its own source.)"
