#!/bin/bash

# Pasal POS Run Script

echo "Building Pasal POS..."
mvn clean compile

if [ $? -eq 0 ]; then
    echo "Build successful! Starting application..."
    mvn javafx:run
else
    echo "Build failed! Please check errors above."
    exit 1
fi



