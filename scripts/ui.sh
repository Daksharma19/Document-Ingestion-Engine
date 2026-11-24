#!/usr/bin/env bash
# React UI (Vite dev server) on :5173. Installs node_modules on first run.
source "$(dirname "$0")/common.sh"
need npm "Node.js 18+"
refuse_if_busy 5173 "Frontend"
[ -d frontend/node_modules ] || npm --prefix frontend install
exec npm --prefix frontend run dev
