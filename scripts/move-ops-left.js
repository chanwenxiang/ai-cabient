const fs = require('fs');
const path = require('path');

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.vue')) out.push(p);
  }
  return out;
}

const files = walk(path.join('clients', 'admin-vue', 'src', 'views'));
let changed = 0;

for (const file of files) {
  let s = fs.readFileSync(file, 'utf8');
  const orig = s;

  s = s.replace(/(<el-table\b[^>]*>)([\s\S]*?)(\n[ \t]*<\/el-table>)/g, (full, open, body, close) => {
    const colRe = /\n([ \t]*)<el-table-column\b[\s\S]*?\n\1<\/el-table-column>/g;
    const matches = [...body.matchAll(colRe)];
    if (!matches.length) return full;

    const firstIdx = matches[0].index;
    const prefix = body.slice(0, firstIdx);
    const restAfter = body.slice(matches[matches.length - 1].index + matches[matches.length - 1][0].length);
    const columns = matches.map((x) => x[0]);
    const opIdx = columns.findIndex((c) => /label="操作"/.test(c));
    if (opIdx <= 0) return full;

    const [op] = columns.splice(opIdx, 1);
    const selIdx = columns.findIndex((c) => /type="selection"/.test(c));
    if (selIdx >= 0) columns.splice(selIdx + 1, 0, op);
    else columns.unshift(op);

    return open + prefix + columns.join('') + restAfter + close;
  });

  if (s !== orig) {
    fs.writeFileSync(file, s, 'utf8');
    changed += 1;
    console.log('moved ops left:', file);
  }
}

console.log('done, files=', changed);
