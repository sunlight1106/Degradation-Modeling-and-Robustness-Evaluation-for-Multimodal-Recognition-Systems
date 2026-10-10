export interface NoteNode { id: string; parentId: string | null }

/** Visit each node once; iterative traversal also supports deeply nested pages. */
export function noteTree<T extends NoteNode>(notes: T[], folded: ReadonlySet<string>, searching = false) {
  const ids = new Set(notes.map(n => n.id))
  const children = new Map<string, T[]>()
  const roots: T[] = []
  for (const n of notes) {
    if (!n.parentId || !ids.has(n.parentId)) roots.push(n)
    else { const list = children.get(n.parentId) || []; list.push(n); children.set(n.parentId, list) }
  }
  const rows: Array<{ note: T; depth: number }> = [], visited = new Set<string>(), hidden = new Set<string>()
  const walk = (start: T, depth = 0, conceal = false) => {
    const stack = [{ note: start, depth, conceal }]
    while (stack.length) {
      const row = stack.pop()!
      if (visited.has(row.note.id)) continue
      visited.add(row.note.id)
      if (row.conceal) hidden.add(row.note.id)
      else rows.push({ note: row.note, depth: Math.min(row.depth, 8) })
      const next = children.get(row.note.id) || []
      for (let i = next.length - 1; i >= 0; i--) stack.push({ note: next[i]!, depth: row.depth + 1, conceal: row.conceal || (!searching && folded.has(row.note.id)) })
    }
  }
  roots.forEach(n => walk(n))
  // Corrupted/imported cycles remain visible rather than disappearing.
  notes.forEach(n => { if (!visited.has(n.id) && !hidden.has(n.id)) walk(n) })
  return { rows, parents: new Set(children.keys()) }
}
