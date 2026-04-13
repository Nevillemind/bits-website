#!/bin/bash
cd "$(dirname "$0")/api"
echo ""
echo "🛡  Starting TRACE Shield..."
echo "   API:       http://localhost:8003"
echo "   API Docs:  http://localhost:8003/docs"
echo "   Shield:    file://$(pwd)/../shield/index.html"
echo "   Check:     file://$(pwd)/../check/index.html"
echo ""
python3 main.py
