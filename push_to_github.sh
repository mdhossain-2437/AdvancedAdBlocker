#!/usr/bin/env bash
set -e

# -------------------------------
# Configure these variables first
# -------------------------------
GITHUB_URL="https://github.com/mdhossain-2437/AdvancedAdBlocker.git"  # <-- Replace with your GitHub repo URL
COMMIT_MSG="Initial commit: Full AdBlocker native + advanced proxy + Docker build"
BRANCH="main"
# -------------------------------

# Check if Git is installed
if ! command -v git &> /dev/null; then
    echo "Error: git not installed."
    exit 1
fi

# Initialize git repo if not already
if [ ! -d ".git" ]; then
    git init
    echo "Initialized new git repo."
fi

# Add remote if not present
if ! git remote | grep -q origin; then
    git remote add origin "$GITHUB_URL"
    echo "Added remote origin: $GITHUB_URL"
fi

# Add all files and commit
git add .
git commit -m "$COMMIT_MSG" || echo "Nothing to commit."

# Set branch name
git branch -M "$BRANCH"

# Push to GitHub
git push -u origin "$BRANCH"

echo "✅ Push complete! Your project is now on GitHub."
