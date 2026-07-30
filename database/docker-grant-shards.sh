#!/bin/sh
set -eu

case "${MYSQL_USER:-}" in
  ''|*[!A-Za-z0-9_]*)
    echo "MYSQL_USER must contain only letters, digits and underscores." >&2
    exit 1
    ;;
esac

for database in \
  waitfans \
  waitfans_carousel \
  waitfans_video_anime \
  waitfans_video_guochuang \
  waitfans_video_douga \
  waitfans_video_game \
  waitfans_video_kichiku \
  waitfans_video_music \
  waitfans_video_dance \
  waitfans_video_cinephile \
  waitfans_video_ent \
  waitfans_video_knowledge \
  waitfans_video_tech \
  waitfans_video_information \
  waitfans_video_food \
  waitfans_video_life \
  waitfans_video_car \
  waitfans_video_fashion \
  waitfans_video_sports \
  waitfans_video_animal \
  waitfans_video_virtual
do
  MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" mysql \
    --protocol=socket \
    --user=root \
    --execute="GRANT ALL PRIVILEGES ON ${database}.* TO '${MYSQL_USER}'@'%';"
done

MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" mysql \
  --protocol=socket \
  --user=root \
  --execute="FLUSH PRIVILEGES;"
