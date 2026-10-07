export interface NoteDraft { title:string;body:string;tags:string;library:string;contentFormat:'HTML'|'MARKDOWN';status:string;parentId:string;revision:number;savedAt:string }
export const draftKey=(owner:number|string,id:string)=>`pkb-draft:${owner}:${id}`
export function readDraft(key:string):NoteDraft|null {
 try{
   const raw=localStorage.getItem(key);if(!raw)return null
   const data=JSON.parse(raw)
   return data && ['body','title','tags','library','parentId','savedAt'].every(k=>typeof data[k]==='string')
     && Number.isInteger(data.revision) && data.revision>=0
     && ['HTML','MARKDOWN'].includes(data.contentFormat) && ['DRAFT','ACTIVE','ARCHIVED'].includes(data.status) ? data : null
 }catch{return null}
}
export function writeDraft(key:string,data:NoteDraft){localStorage.setItem(key,JSON.stringify(data))}
export function clearDraft(key:string){localStorage.removeItem(key)}
