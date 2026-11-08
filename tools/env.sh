# Source this to build/run with the toolchain in ~/tools (JDK 17 + Android SDK):
#   source tools/env.sh
export ETRACKER_TOOLS="${ETRACKER_TOOLS:-$HOME/tools}"
export JAVA_HOME="$ETRACKER_TOOLS/jdk17"
export ANDROID_HOME="$ETRACKER_TOOLS/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
