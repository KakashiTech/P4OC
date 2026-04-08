#!/bin/bash

# Enhanced theme validation script
# Checks for theme violations and ensures consistent theme usage

echo "🔍 Enhanced Theme Validation"
echo "=============================="

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

VIOLATIONS_FOUND=0

# 1. Check for MaterialTheme.colorScheme usage (should use LocalOpenCodeTheme)
echo -e "${BLUE}1. Checking MaterialTheme.colorScheme usage...${NC}"
COLOR_SCHEME_VIOLATIONS=$(grep -r "MaterialTheme\.colorScheme\." app/src/main/java/dev/blazelight/p4oc/ui/ 2>/dev/null | grep -v "check_theme_violations" | head -10)

if [ -n "$COLOR_SCHEME_VIOLATIONS" ]; then
    echo -e "${RED}  ❌ Direct MaterialTheme.colorScheme usage found:${NC}"
    echo "$COLOR_SCHEME_VIOLATIONS" | while read line; do
        echo "    $line"
    done
    VIOLATIONS_FOUND=$((VIOLATIONS_FOUND + 1))
else
    echo -e "${GREEN}  ✅ No direct MaterialTheme.colorScheme usage${NC}"
fi

# 2. Check for hardcoded Color.Transparent (should use SemanticColors.Common.transparent)
echo -e "${BLUE}2. Checking Color.Transparent usage...${NC}"
TRANSPARENT_VIOLATIONS=$(grep -r "Color\.Transparent" app/src/main/java/dev/blazelight/p4oc/ui/ 2>/dev/null | grep -v "check_theme_violations" | head -10)

if [ -n "$TRANSPARENT_VIOLATIONS" ]; then
    echo -e "${YELLOW}  ⚠️  Color.Transparent usage found (consider SemanticColors.Common.transparent):${NC}"
    echo "$TRANSPARENT_VIOLATIONS" | while read line; do
        echo "    $line"
    done
    VIOLATIONS_FOUND=$((VIOLATIONS_FOUND + 1))
else
    echo -e "${GREEN}  ✅ No Color.Transparent usage${NC}"
fi

# 3. Check for hardcoded Color values (should use theme colors)
echo -e "${BLUE}3. Checking hardcoded Color values...${NC}"
HARDCODED_COLORS=$(grep -r "Color(0x" app/src/main/java/dev/blazelight/p4oc/ui/ 2>/dev/null | grep -v "check_theme_violations" | head -10)

if [ -n "$HARDCODED_COLORS" ]; then
    echo -e "${RED}  ❌ Hardcoded Color values found:${NC}"
    echo "$HARDCODED_COLORS" | while read line; do
        echo "    $line"
    done
    VIOLATIONS_FOUND=$((VIOLATIONS_FOUND + 1))
else
    echo -e "${GREEN}  ✅ No hardcoded Color values${NC}"
fi

# 4. Check for missing LocalOpenCodeTheme usage in UI components
echo -e "${BLUE}4. Checking theme consistency in UI components...${NC}"
THEME_USAGE=$(grep -r "LocalOpenCodeTheme\.current\." app/src/main/java/dev/blazelight/p4oc/ui/ 2>/dev/null | wc -l)
echo -e "${GREEN}  ✅ Found $THEME_USAGE proper theme usages${NC}"

# 5. Check if all screens use proper theme wrapping
echo -e "${BLUE}5. Checking theme wrapper usage...${NC}"
POCKET_CODE_THEME_USAGE=$(grep -r "PocketCodeTheme" app/src/main/java/dev/blazelight/p4oc/ 2>/dev/null | wc -l)
echo -e "${GREEN}  ✅ Found $POCKET_CODE_THEME_USAGE PocketCodeTheme usages${NC}"

# 6. Summary
echo -e "${BLUE}6. Validation Summary${NC}"
if [ $VIOLATIONS_FOUND -gt 0 ]; then
    echo -e "${RED}❌ Found $VIOLATIONS_FOUND violation type(s) that need attention${NC}"
    echo -e "${YELLOW}💡 Run: ./gradlew :app:compileDebugKotlin to verify compilation${NC}"
    exit 1
else
    echo -e "${GREEN}✅ All theme validations passed!${NC}"
    echo -e "${GREEN}🎨 Theme system is properly implemented${NC}"
    exit 0
fi
