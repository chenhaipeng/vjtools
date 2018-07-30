#!/bin/sh

if [ -z "$JAVA_HOME" ] ; then
	    echo "JAVA_HOME env doesn't exist, try to find the location of java"
        JAVA_HOME=`readlink -f \`which java 2>/dev/null\` 2>/dev/null | \
        sed 's/\jre\/bin\/java//' | sed 's/\/bin\/java//'`
fi

TOOLSJAR="$JAVA_HOME/lib/tools.jar"

if [ ! -f "$TOOLSJAR" ] ; then
    echo "JAVA_HOME is $JAVA_HOME, $TOOLSJAR doesn't exist" >&2
    exit 1
fi

DIR=$( cd $(dirname $0) ; pwd -P )

JAVA_OPTS="-Xms96m -Xmx96m -Xmn64m -Xss256k -XX:ReservedCodeCacheSize=2496k -XX:AutoBoxCacheMax=20000 -XX:+UseSerialGC -Djava.compiler=NONE -Xverify:none" 

"$JAVA_HOME"/bin/java $JAVA_OPTS -cp "$DIR/vjmxcli.jar:$TOOLSJAR" com.vip.vjtools.jmx.Client $*
