import { build } from 'esbuild';
await build({entryPoints:['web/App.jsx'],bundle:true,minify:true,outfile:'web/app.js',format:'iife',platform:'browser',jsx:'automatic',define:{'process.env.NODE_ENV':'"production"'},legalComments:'eof'});
console.log('Built React browser bundle: web/app.js');
