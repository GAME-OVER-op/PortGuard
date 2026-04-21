.PHONY: all apk module doctor clean clear

all: apk

apk:
	./build.sh apk

module:
	./build.sh module

doctor:
	./build.sh doctor

clean:
	./build.sh clean

clear:
	./build.sh clear
