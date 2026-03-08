#!/bin/sh
#
# Gradle wrapper script — needed for GitHub Actions build
#

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`

# Default JVM options
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Resolve links
PRG="$0"
while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=`dirname "$PRG"`"/$link"
    fi
done
SAVED="`pwd`"
cd "`dirname \"$PRG\"`/" >/dev/null
APP_HOME="`pwd -P`"
cd "$SAVED" >/dev/null

exec "$JAVACMD" "$@"
