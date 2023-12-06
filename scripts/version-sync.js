#!/usr/bin/env node

const fs = require('fs');
const {version} = require('../package.json');

function updateFileVersionRefs(filePath, replaceRegex, replacer) {
    let fileData = fs.readFileSync(filePath).toString();
    fileData = fileData.replace(replaceRegex, replacer);
    fs.writeFileSync(filePath, fileData);
}

function main() {
    updateFileVersionRefs(`./plugin.xml`, /(version=")([^"]+)(">)/gm, `$1${version}$3`);
}

main();