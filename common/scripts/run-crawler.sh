#!/usr/bin/env bash
ssh -p 3031 root@master '
cd /root/project/ehsan/Keenbo/target/bin;
./start-crawler.sh > /dev/null
'