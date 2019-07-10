# Redis
# Version 5.0.5

FROM centos:7
ADD redis-5.0.5.tar.gz /
ENV REDIS_HOME /usr/local
ADD redis.conf $REDIS_HOME/redis/
RUN mkdir -p $REDIS_HOME/redis

RUN yum -y update
RUN yum install -y gcc make

WORKDIR /redis-5.0.5
RUN make
RUN mv /redis-5.0.5/src/redis-server  $REDIS_HOME/redis/

WORKDIR /

RUN yum remove -y gcc make

VOLUME ["/var/log/redis"]

EXPOSE 6379