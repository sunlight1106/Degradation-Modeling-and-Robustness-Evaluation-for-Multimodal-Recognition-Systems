import { test } from 'node:test'
import assert from 'node:assert/strict'
import { noteTree } from '../lib/noteTree.ts'
test('deep trees do not overflow and folding conceals all descendants',()=>{const nodes=Array.from({length:5000},(_,n)=>({id:String(n),parentId:n?String(n-1):null}));assert.equal(noteTree(nodes,new Set()).rows.length,5000);assert.equal(noteTree(nodes,new Set(['0'])).rows.length,1);assert.equal(noteTree(nodes,new Set(['0']),true).rows.length,5000)})
test('cycles and orphan pages remain visible exactly once',()=>{const nodes=[{id:'a',parentId:'b'},{id:'b',parentId:'a'},{id:'c',parentId:'missing'}];const result=noteTree(nodes,new Set());assert.deepEqual(new Set(result.rows.map(r=>r.note.id)),new Set(['a','b','c']))})
