// Mechanical migration of known presentation literals only. Does not touch Lua sources,
// diagnostics, tests, JSON keys, API implementations, or Java text-block examples.
import fs from 'node:fs';
const directory = 'src/client/java/wiki/ica/xiaomiao/mcluaide/client/';
const translations = JSON.parse(fs.readFileSync('src/main/resources/assets/mc_lua_ide/i18n/en_us.json', 'utf8'));
let replacements = 0, files = 0;
for (const name of fs.readdirSync(directory)) {
  if (!/(Screen|Overlay|Window|Hud|JsonTreeEditor|LuaApiMarkdownExporter)\.java$/.test(name) || /(Probe|Smoke)/.test(name)) continue;
  const saved = fs.readFileSync(directory + name, 'utf8');
  let original = saved;
  // Static navigation labels stay canonical; their accessor translates on each use.
  const headerEnd = name === 'LuaApiDocsOverlay.java' ? original.indexOf('private final Minecraft')
    : name === 'LuaPersonalizationScreen.java' ? original.indexOf('private final LuaIdeScreen') : 0;
  if (headerEnd > 0) original = original.slice(0, headerEnd).replace(/LuaUiText\.text\(("(?:[^"\\]|\\.)*")\)/g, '$1') + original.slice(headerEnd);
  const canonicalHeaderEnd = headerEnd > 0 ? original.indexOf(name === 'LuaApiDocsOverlay.java' ? 'private final Minecraft' : 'private final LuaIdeScreen') : 0;
  const staticSections = [...original.matchAll(/private static final ApiSection[\s\S]*?;/g)].map(match => [match.index, match.index + match[0].length]);
  let output = '', index = 0, count = 0;
  while (index < original.length) {
    const start = index;
    if (original.startsWith('//', index)) {
      index = original.indexOf('\n', index); if (index < 0) index = original.length;
    } else if (original.startsWith('/*', index)) {
      const end = original.indexOf('*/', index + 2); index = end < 0 ? original.length : end + 2;
    } else if (original.startsWith('"""', index)) {
      const end = original.indexOf('"""', index + 3); index = end < 0 ? original.length : end + 3;
    } else if (original[index] === '"' || original[index] === "'") {
      const quote = original[index++];
      while (index < original.length) { if (original[index++] === '\\') index++; else if (original[index - 1] === quote) break; }
      const literal = original.slice(start, index);
      if (quote === '"' && Object.hasOwn(translations, literal.slice(1, -1))
          && start >= canonicalHeaderEnd
          && !staticSections.some(([from, to]) => start >= from && start < to)
          && !original.slice(Math.max(0, start - 30), start).endsWith('LuaUiText.text(')) {
        output += 'LuaUiText.text(' + literal + ')'; count++; continue;
      }
    } else index++;
    output += original.slice(start, index);
  }
  if (count || saved !== output) {
    files++; replacements += count;
    if (process.argv.includes('--apply')) fs.writeFileSync(directory + name, output);
    console.log(`${name}: ${count}`);
  }
}
console.log(`${replacements} known UI literals across ${files} files`);
