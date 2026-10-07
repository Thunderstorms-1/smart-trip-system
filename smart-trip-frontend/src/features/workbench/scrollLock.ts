// Shared by nested editor dialogs. Each surface releases only its own lock.
const owners = new Set<symbol>();
let previous = "";
export function lockEditorScroll() {
  const owner = Symbol("editor-dialog");
  if (!owners.size) {
    previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
  }
  owners.add(owner);
  return () => {
    if (!owners.delete(owner)) return;
    if (!owners.size) document.body.style.overflow = previous;
  };
}
