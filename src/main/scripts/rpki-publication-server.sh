#!/usr/bin/env bash
#
# The BSD License
#
# Copyright (c) 2010-2015 RIPE NCC
# All rights reserved.
#
# Redistribution and use in source and binary forms, with or without
# modification, are permitted provided that the following conditions are met:
#   - Redistributions of source code must retain the above copyright notice,
#     this list of conditions and the following disclaimer.
#   - Redistributions in binary form must reproduce the above copyright notice,
#     this list of conditions and the following disclaimer in the documentation
#     and/or other materials provided with the distribution.
#   - Neither the name of the RIPE NCC nor the names of its contributors may be
#     used to endorse or promote products derived from this software without
#     specific prior written permission.
#
# THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
# AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
# IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
# ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
# LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
# CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
# SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
# INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
# CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
# ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
# POSSIBILITY OF SUCH DAMAGE.
#

EXECUTION_DIR=$(dirname "${BASH_SOURCE[0]}")

APP_NAME="rpki-publication-server"
PID_FILE=${EXECUTION_DIR}/${APP_NAME}.pid

function error_exit {
    echo -e "[ error ] $1"
    exit 1
}

function info {
    echo -e "[ info ] $1"
}

function warn {
    echo -e "[ warn ] $1"
}

function usage {
cat << EOF
Usage: $0 start  [-c /path/to/my-configuration.conf]
   or  $0 run    [-c /path/to/my-configuration.conf]
   or  $0 stop   [-c /path/to/my-configuration.conf]
   or  $0 status [-c /path/to/my-configuration.conf]
EOF
}

function resolve_path {
    local dir="$1"
    local path="$2"
    local resolved_dir

    if ! resolved_dir=$(cd "$dir" && cd "$(dirname "$path")" && pwd); then
        exit 1
    fi

    echo "$resolved_dir"/"$(basename "$path")"
}

#
# Specify the location of the Java home directory. If set then $JAVA_CMD will
# be defined to $JAVA_HOME/bin/java
#
if [ -d "${JAVA_HOME}"  ] ; then
    JAVA_CMD="${JAVA_HOME}/bin/java"
else
    warn "JAVA_HOME is not set, will try to find java on path."
    JAVA_CMD=$(which java)
fi

if [ -z "$JAVA_CMD" ]; then
    error_exit "Cannot find java on path. Make sure java is installed and/or set JAVA_HOME"
fi

# See how we're called
FIRST_ARG="$1"
shift
if [[ -n $MODE ]]; then
   usage
   exit
fi



# Determine config file location
# shellcheck disable=SC2034
getopts ":c:" OPT_NAME
CONFIG_FILE=${OPTARG:-conf/rpki-publication-server.default.conf}

function check_config_location {
    if [[ ! $CONFIG_FILE =~ .*conf$ ]]; then
        error_exit "Configuration file name must end with .conf"
    fi

    if [[ ! -r $CONFIG_FILE ]]; then
        error_exit "Can't read config file: $CONFIG_FILE"
    fi
}

function parse_config_line {
    local CONFIG_KEY=$1
    local VALUE
    VALUE=$(grep "^$CONFIG_KEY" "$CONFIG_FILE" | sed 's/#.*//g' | sed 's/^.*=[[:space:]]*\(.*\)/\1/g')

    if [ -z "$VALUE" ]; then
        error_exit "Cannot find value for: $CONFIG_KEY in config-file: $CONFIG_FILE"
    fi
    eval "$2=$VALUE"
}

#
# Determine if the application is already running
#
RUNNING="false"
if [ -e "${PID_FILE}" ]; then
    if pgrep -F "${PID_FILE}" "\-Dapp.name=${APP_NAME}" >/dev/null 2>&1; then
        RUNNING="true"
    fi
fi


case ${FIRST_ARG} in
    start|run)
        if [ ${RUNNING} == "true" ]; then
            error_exit "${APP_NAME} is already running"
        fi

        check_config_location

        parse_config_line "locations.logfile" LOG_FILE
        LOG_FILE=$(resolve_path "$(dirname "$CONFIG_FILE")" "$LOG_FILE")

        parse_config_line "locations.logfile" GC_LOG_FILE
        GC_LOG_FILE=$(resolve_path "$(dirname "$CONFIG_FILE")" "$GC_LOG_FILE")

        parse_config_line "jvm.memory.initial" JVM_XMS
        parse_config_line "jvm.memory.maximum" JVM_XMX

        info "Starting ${APP_NAME}..."
        info "Publication server is available on port 7788"

        CLASSPATH=:"${EXECUTION_DIR}/../lib:${EXECUTION_DIR}/../lib/*"
        MEM_OPTIONS="-Xms$JVM_XMS -Xmx$JVM_XMX -XX:+HeapDumpOnOutOfMemoryError"
        GC_LOG_OPTIONS="-XX:+PrintGCDetails -XX:+PrintGCDateStamps -verbose:gc -Xloggc:${GC_LOG_FILE} -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=2"

        CMDLINE="${JAVA_CMD} ${MEM_OPTIONS} ${GC_LOG_OPTIONS} \
                 -Dapp.name=${APP_NAME} -Dconfig.file=${CONFIG_FILE} -Dlog.file=${LOG_FILE} \
                 -classpath ${CLASSPATH} net.ripe.rpki.publicationserver.Boot"

        echo "CMDLINE=${CMDLINE}"

        if [ "${FIRST_ARG}" == "start" ]; then
            ${CMDLINE} &
        elif [ "${FIRST_ARG}" == "run" ]; then
            exec ${CMDLINE}
        fi

        PID=$!
        echo $PID > "$PID_FILE"
        info "Writing PID ${PID} to ${PID_FILE}"
        ;;
    stop)
        info "Stopping ${APP_NAME}..."
        if [ ${RUNNING} == "true" ]; then
            pkill -F "${PID_FILE}" && rm "${PID_FILE}"
        else
            info "${APP_NAME} in not running"
        fi
        ;;
    status)
        if [ ${RUNNING} == "true" ]; then
            info "${APP_NAME} is running"
            exit 0
        else
            info "${APP_NAME} is not running"
            exit 0
        fi
        ;;
    *)
        usage
        exit
        ;;
esac

exit $?
