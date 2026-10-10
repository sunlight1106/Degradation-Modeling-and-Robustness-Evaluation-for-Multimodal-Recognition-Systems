export interface DiffLine {kind:'same'|'remove'|'add';oldLine:number|null;newLine:number|null;text:string}
/** Linear prefix/suffix comparison, bounded display. Full drafts remain untouched. */
export function diffLines(before:string,after:string,limit=800):DiffLine[]{
 const a=before.split('\n'),b=after.split('\n');let start=0,end=0
 while(start<a.length&&start<b.length&&a[start]===b[start])start++
 while(end<a.length-start&&end<b.length-start&&a[a.length-end-1]===b[b.length-end-1])end++
 const out:DiffLine[]=[]
 for(let i=Math.max(0,start-3);i<start;i++)out.push({kind:'same',oldLine:i+1,newLine:i+1,text:a[i]})
 for(let i=start;i<a.length-end&&out.length<limit;i++)out.push({kind:'remove',oldLine:i+1,newLine:null,text:a[i]})
 for(let i=start;i<b.length-end&&out.length<limit;i++)out.push({kind:'add',oldLine:null,newLine:i+1,text:b[i]})
 for(let i=0;i<Math.min(end,3)&&out.length<limit;i++)out.push({kind:'same',oldLine:a.length-end+i+1,newLine:b.length-end+i+1,text:a[a.length-end+i]})
 return out
}
function change(base:string[],next:string[]){let start=0,end=0;while(start<base.length&&start<next.length&&base[start]===next[start])start++;while(end<base.length-start&&end<next.length-start&&base[base.length-end-1]===next[next.length-end-1])end++;return {start,end:base.length-end,replacement:next.slice(start,next.length-end)}}
/** Merge disjoint changed ranges; overlapping edits always require human review. */
export function mergeDraft(base:string,ours:string,theirs:string):string|null{
 if(ours===theirs)return ours;if(base===ours)return theirs;if(base===theirs)return ours
 const original=base.split('\n'),a=change(original,ours.split('\n')),b=change(original,theirs.split('\n'))
 if(a.end>b.start&&b.end>a.start||a.start===b.start)return null
 const edits=[a,b].sort((x,y)=>y.start-x.start);for(const e of edits)original.splice(e.start,e.end-e.start,...e.replacement);return original.join('\n')
}
