#!/bin/bash
### BEGIN INIT INFO
# Provides:        ranger-resource-mapping-manager
# Required-Start:  $local_fs $remote_fs $network $named $syslog $time
# Required-Stop:   $local_fs $remote_fs $network $named $syslog $time
# Default-Start:   2 3 4 5
# Default-Stop:
# Short-Description: Start/Stop ranger-resource-mapping-manager
### END INIT INFO

LINUX_USER="${LINUX_USER:-ranger}"
BIN_PATH="${BIN_PATH:-/usr/lib/ranger-rmm}"
MOD_NAME="${MOD_NAME:-ranger-resource-mapping-manager-services.sh}"
RMM_PID_DIR_PATH="${RMM_PID_DIR_PATH:-/var/run/ranger}"
RMM_PID_NAME="${RMM_PID_NAME:-resource-mapping-manager.pid}"
pidf="${RMM_PID_DIR_PATH}/${RMM_PID_NAME}"
pid=""
if [ -f "${pidf}" ]; then
  pid="$(cat "${pidf}" 2>/dev/null || true)"
fi

run_as_target_user() {
  if [ "$(id -un)" = "${LINUX_USER}" ]; then
    eval "$1"
  else
    if command -v runuser >/dev/null 2>&1; then
      /sbin/runuser -u "${LINUX_USER}" -- bash -lc "$1"
    else
      /bin/su -s /bin/bash - "${LINUX_USER}" -c "$1"
    fi
  fi
}

if [ ! -x "${BIN_PATH}/${MOD_NAME}" ]; then
  echo "ERROR: Not found executable ${BIN_PATH}/${MOD_NAME}"
  exit 1
fi

case "$1" in
  start)
    if [ -n "${pid}" ]; then
      echo "Ranger resource-mapping-manager Service is already running [pid=${pid}]"
      exit 1
    fi
    if [ ! -d "${RMM_PID_DIR_PATH}" ]; then
      mkdir -p "${RMM_PID_DIR_PATH}" 2>/dev/null || true
    fi
    echo "Starting Ranger resource-mapping-manager."
    run_as_target_user "${BIN_PATH}/${MOD_NAME} start"
    exit $?
    ;;
  stop)
    if [ -n "${pid}" ]; then
      echo "Stopping Ranger resource-mapping-manager."
      run_as_target_user "${BIN_PATH}/${MOD_NAME} stop"
      exit $?
    else
      echo "Ranger resource-mapping-manager Service is NOT running"
      exit 1
    fi
    ;;
  restart)
    if [ -n "${pid}" ]; then
      echo "Stopping Ranger resource-mapping-manager."
      run_as_target_user "${BIN_PATH}/${MOD_NAME} stop"
      sleep 10
    fi
    echo "Starting Ranger resource-mapping-manager."
    run_as_target_user "${BIN_PATH}/${MOD_NAME} start"
    exit $?
    ;;
  status)
    if [ -n "${pid}" ]; then
      if ps -p "${pid}" >/dev/null 2>&1; then
        echo "Ranger resource-mapping-manager Service is running [pid=${pid}]"
        exit 0
      else
        echo "PID file exists (${pidf}) but process ${pid} is not running."
        exit 3
      fi
    else
      echo "Ranger resource-mapping-manager Service is NOT running."
      exit 3
    fi
    ;;
  *)
    echo "Invalid argument [$1]; Only start | stop | restart | status are supported."
    exit 1
    ;;
esac
