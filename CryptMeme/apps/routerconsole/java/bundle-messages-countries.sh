#!/bin/sh

# Update messages_xx.po and messages_xx.class files,
# from both java and jsp sources.
# Requires installed programs xgettext, msgfmt, msgmerge, and find.
#
# usage:
#    bundle-messages.sh (generates the resource bundle from the .po file)
#    bundle-messages.sh -p (updates the .po file from the source tags, then generates the resource bundle)
#
# zzz - public domain
#
CLASS=net.i2p.router.countries.messages
TMPFILE=build/javafiles-countries.txt
export TZ=UTC
RC=0

if ! $(which javac > /dev/null 2>&1); then
    export JAVAC=${JAVA_HOME}/../bin/javac
fi

if [ "$1" = "-p" ]
then
	POUPDATE=1
fi

# on windows, one must specify the path of commnad find
# since windows has its own version of find.
if which find|grep -q -i windows ; then
	export PATH=.:/bin:/usr/local/bin:$PATH
fi
# Fast mode - update ondemond
# set LG2 to the language you need in envrionment varibales to enable this

#
# generate strings/Countries.java from ../../../installer/resources/countries.txt
#
CFILE=../../../installer/resources/countries.txt
# add ../java/ so the refs will work in the po file
JFILE=../java/build/Countries.java
if [ $CFILE -nt $JFILE -o ! -s $JFILE ]
then
	mkdir -p build
        echo '// Automatically generated pseudo-java for xgettext - do not edit' > $JFILE
	echo '// Translators may wish to translate a few of these, do not bother to translate all of them!!' >> $JFILE
	sed -e '/^#/d' -e 's/..,\(..*\)/_("\1");/' $CFILE >> $JFILE
fi

# add ../java/ so the refs will work in the po file
JPATHS="$JFILE"
for i in ../locale-countries/messages_*.po
do
	# get language
	LG=${i#../locale-countries/messages_}
	LG=${LG%.po}

	# skip, if specified
	if [ $LG2 ]; then
		[ $LG != $LG2 ] && continue || echo INFO: Language update is set to [$LG2] only.
	fi

	if [ "$POUPDATE" = "1" ]
	then
		# make list of java files newer than the .po file
		find $JPATHS -name *.java -newer $i > $TMPFILE
	fi
	if [ -s build/obj/net/i2p/router/countries/messages_$LG.class -a \
	     build/obj/net/i2p/router/countries/messages_$LG.class -nt $i -a \
	     ! -s $TMPFILE ]
	then
		continue
	fi

	if [ "$POUPDATE" = "1" ]
	then
	 	echo "Updating the $i file from the tags..."
		# extract strings from java and jsp files, and update messages.po files
		# translate calls must be one of the forms:
		# _("foo")
		# _x("foo")
		# intl._("foo")
		# intl.title("foo")
		# In a jsp, you must use a helper or handler that has the context set.
		# To start a new translation, copy the header from an old translation to the new .po file,
		# then ant distclean updater.
		find $JPATHS -name *.java > $TMPFILE
		xgettext -f $TMPFILE -L java --from-code=UTF-8 --no-location\
	                 --keyword=_ \
		         -o ${i}t
		if [ $? -ne 0 ]
		then
			echo "ERROR - xgettext failed on ${i}, not updating translations"
			rm -f ${i}t
			RC=1
			break
		fi
		msgmerge -U --backup=none $i ${i}t
		if [ $? -ne 0 ]
		then
			echo "ERROR - msgmerge failed on ${i}, not updating translations"
			rm -f ${i}t
			RC=1
			break
		fi
		rm -f ${i}t
		# so we don't do this again
		touch $i
	fi

    if [ "$LG" != "en" ]
    then
        # only generate for non-source language
        echo "Generating ${CLASS}_$LG ResourceBundle..."

        # convert to class files in build/obj
        msgfmt --java --statistics -r $CLASS -l $LG -d build/obj $i
        if [ $? -ne 0 ]
        then
            echo "ERROR - msgfmt failed on ${i}, not updating translations"
            # msgfmt leaves the class file there so the build would work the next time
            find build/obj -name messages_${LG}.class -exec rm -f {} \;
            RC=1
            break
        fi
    fi
done
rm -f $TMPFILE
exit $RC
