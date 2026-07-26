#!/bin/sh
set -eu

for index in video user search_word; do
  if curl -fsSI "http://elasticsearch:9200/${index}" >/dev/null; then
    echo "Elasticsearch index '${index}' already exists."
  else
    curl -fsS \
      -X PUT \
      -H "Content-Type: application/json" \
      --data-binary "@/mappings/${index}.json" \
      "http://elasticsearch:9200/${index}"
    echo
    echo "Created Elasticsearch index '${index}'."
  fi
done
