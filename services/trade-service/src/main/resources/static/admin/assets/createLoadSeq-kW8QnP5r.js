function u(){let r=0;const n=new Map;return{begin(e){if(e){const t=(n.get(e)||0)+1;return n.set(e,t),t}return r+=1,r},isCurrent(e,t){return t?n.get(t)===e:e===r}}}export{u as c};
