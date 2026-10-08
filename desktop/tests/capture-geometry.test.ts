import assert from 'node:assert/strict';
import test from 'node:test';
import { rectangle, pixelRectangle } from '../src/capture-geometry';
test('reverse dragging produces a positive top-left selection', () => {
  assert.deepEqual(rectangle({x:200,y:180}, {x:20,y:30}), {x:20,y:30,width:180,height:150});
});
test('high DPI screen selection uses image pixels instead of CSS coordinates', () => {
  assert.deepEqual(pixelRectangle({x:30,y:50,width:200,height:80}, {width:1280,height:720}, {width:2560,height:1440}), {x:60,y:100,width:400,height:160});
});
test('fractional scaling rounds edges and clamps to screenshot bounds', () => {
  const result = pixelRectangle({x:1010,y:710,width:300,height:30}, {width:1280,height:720}, {width:1920,height:1080});
  assert.deepEqual(result, {x:1515,y:1065,width:405,height:15});
  assert.throws(() => pixelRectangle({x:0,y:0,width:4,height:8}, {width:0,height:720}, {width:1920,height:1080}));
});
