import { createRenderer } from "vue";

export type TestNode = {
  tag: string;
  text: string;
  props: Record<string, unknown>;
  children: TestNode[];
  parent: TestNode | null;
  showModal: () => void;
};
export const node = (tag: string, text = ""): TestNode => ({
  tag,
  text,
  props: {},
  children: [],
  parent: null,
  showModal: () => {},
});
function remove(el: TestNode) {
  if (el.parent) {
    const siblings = el.parent.children;
    siblings.splice(siblings.indexOf(el), 1);
    el.parent = null;
  }
}
export const renderer = createRenderer<TestNode, TestNode>({
  createElement: (tag) => node(tag),
  createText: (text) => node("#text", text),
  createComment: (text) => node("#comment", text),
  setText: (el, text) => {
    el.text = text;
  },
  setElementText: (el, text) => {
    el.text = text;
    el.children = [];
  },
  parentNode: (el) => el.parent,
  nextSibling: (el) =>
    el.parent?.children[el.parent.children.indexOf(el) + 1] || null,
  insert: (el, parent, anchor) => {
    remove(el);
    el.parent = parent;
    const i = anchor ? parent.children.indexOf(anchor) : -1;
    if (i < 0) parent.children.push(el);
    else parent.children.splice(i, 0, el);
  },
  remove,
  patchProp: (el, key, _previous, value) => {
    el.props[key] = value;
  },
});
export function find(
  el: TestNode,
  predicate: (candidate: TestNode) => boolean,
): TestNode | undefined {
  if (predicate(el)) return el;
  for (const child of el.children) {
    const result = find(child, predicate);
    if (result) return result;
  }
}
export function text(el: TestNode): string {
  return el.text + el.children.map(text).join("");
}
