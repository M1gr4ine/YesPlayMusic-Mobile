const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../assets/app.js'), 'utf8');
const start = source.indexOf('  function artistNames(');
const end = source.indexOf('  function extractSongs(', start);
const context = vm.createContext({safeHttpsUrl: value => String(value || '')});
vm.runInContext(source.slice(start, end) + '\nthis.normalizeTrack = normalizeTrack;', context);
const normalize = track => JSON.parse(JSON.stringify(context.normalizeTrack(track, 'netease')));

test('keeps artist aliases from provider metadata without changing the displayed artist', () => {
  const track = normalize({id:1372726248,name:'\u65b0\u5b9d\u5cf6',dt:305000,ar:[{name:'\u30b5\u30ab\u30ca\u30af\u30b7\u30e7\u30f3',alias:['Sakanaction'],alia:['Sakanaction'],tns:['\u9c7c\u97f5']}],al:{name:'834.194'}});
  assert.equal(track.artist, '\u30b5\u30ab\u30ca\u30af\u30b7\u30e7\u30f3');
  assert.deepEqual(track.artistAliases, ['Sakanaction', '\u9c7c\u97f5']);
  assert.equal(track.provider, 'netease');
});

test('preserves aliases when queued metadata is normalized again', () => {
  const track = normalize({id:'1372726248',title:'\u65b0\u5b9d\u5cf6',artist:'\u30b5\u30ab\u30ca\u30af\u30b7\u30e7\u30f3',artistAliases:['Sakanaction','\u9c7c\u97f5'],provider:'netease',duration:305000});
  assert.deepEqual(track.artistAliases, ['Sakanaction','\u9c7c\u97f5']);
});

test('uses only bounded string aliases and accepts a provider translation field', () => {
  const raw={id:1,name:'Song',ar:[{name:'Artist',alias:['  Known  ','Known',{},0],trans:'Translation'}]};
  const track=normalize(raw);
  assert.deepEqual(track.artistAliases,['Known','Translation']);
});

test('does not invent an alias for an unrelated artist', () => {
  const track=normalize({id:1,name:'Song',artist:'Unrelated singer'});
  assert.deepEqual(track.artistAliases,[]);
  assert.equal(track.artist,'Unrelated singer');
});
