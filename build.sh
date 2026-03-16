#!/bin/sh
prog1="Node"

echo "> cleaning up"
rm -rf classes lib

echo "> compiling ..."

#javac -d classes src/*.java

javac -cp ./dependencies/amqp-client-5.16.0.jar $prog1.java

