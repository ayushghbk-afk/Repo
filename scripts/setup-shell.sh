#!/bin/bash
# Lenix Shell Setup Script
# Fixes shell issues and sets up development environment

set -e

echo "=== Lenix Shell Setup ==="
echo ""

# 1. Fix /etc/profile - use absolute path for id command
echo "1. Fixing /etc/profile..."
if [ -f /etc/profile ]; then
    sudo cp /etc/profile /etc/profile.bak
    sudo sed -i 's|\$(id -u)|$(/usr/bin/id -u)|g' /etc/profile
    echo "   Fixed: /etc/profile now uses /usr/bin/id"
fi

# 2. Create ~/bin directory
echo "2. Creating ~/bin directory..."
mkdir -p ~/bin

# 3. Create nano wrapper
echo "3. Creating nano editor wrapper..."
cat > ~/bin/nano << 'NANO_SCRIPT'
#!/bin/bash
# nano wrapper - uses busybox vi when nano not available

if [ "$1" = "--help" ] || [ "$1" = "-h" ] || [ "$1" = "-v" ]; then
    echo "nano wrapper (busybox vi fallback)"
    echo "Usage: nano [OPTIONS] [FILE]"
    echo "Options: -h, --help    Show help"
    echo "         FILE          Edit or create FILE"
    exit 0
fi

FILE="$1"

if [ -z "$FILE" ]; then
    echo "nano: missing file operand" >&2
    echo "Usage: nano [FILE]" >&2
    exit 1
fi

if [ ! -f "$FILE" ]; then
    touch "$FILE" 2>/dev/null || {
        echo "nano: cannot create '$FILE'" >&2
        exit 1
    }
fi

# View or edit based on TTY
if [ ! -t 0 ]; then
    cat "$FILE"
    exit 0
fi

if command -v busybox &>/dev/null; then
    exec busybox vi "$FILE" < /dev/tty > /dev/tty 2>&1
elif command -v vi &>/dev/null; then
    exec vi "$FILE"
elif command -v vim &>/dev/null; then
    exec vim "$FILE"
elif command -v nano &>/dev/null; then
    exec nano "$FILE"
else
    echo "No editor available" >&2
    cat "$FILE"
    exit 0
fi
NANO_SCRIPT
chmod +x ~/bin/nano
echo "   Created ~/bin/nano"

# 4. Add PATH to .bashrc
echo "4. Configuring PATH..."
if ! grep -q 'export PATH=.*HOME/bin' ~/.bashrc; then
    echo '' >> ~/.bashrc
    echo '# Add ~/bin to PATH' >> ~/.bashrc
    echo 'export PATH="$HOME/bin:$PATH"' >> ~/.bashrc
    echo "   Added PATH to ~/.bashrc"
fi

# 5. Add aliases
echo "5. Adding aliases..."
if ! grep -q 'alias nano=' ~/.bashrc; then
    cat >> ~/.bashrc << 'ALIASES'

# Lenix development aliases
alias nano='~/bin/nano'
alias edit='~/bin/nano'
ALIASES
    echo "   Added aliases to ~/.bashrc"
fi

echo ""
echo "=== Setup Complete ==="
echo ""
echo "Usage:"
echo "  nano <file>    Edit file"
echo "  nano --help    Show help"
echo ""
echo "Note: For real nano, run: sudo apt-get install nano"
