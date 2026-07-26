FROM docker.elastic.co/elasticsearch/elasticsearch:7.17.16

RUN bin/elasticsearch-plugin install --batch \
      https://get.infini.cloud/elasticsearch/analysis-ik/7.17.16 \
    && bin/elasticsearch-plugin install --batch \
      https://get.infini.cloud/elasticsearch/analysis-pinyin/7.17.16
