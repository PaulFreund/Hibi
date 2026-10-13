let editor, originalMarkdown='', canonicalInitial='', ready=false, changeTimer, maximumTimer;
function sizeViewport() {
 // Android WebView can resolve percentage/vh document heights to zero even
 // after its measured window has a nonzero height. Keep the root definite.
 if(window.innerHeight>0) {
  const height=window.innerHeight+'px';
  document.documentElement.style.height=height;
  document.documentElement.style.setProperty('--viewport-height',height);
 }
}
window.addEventListener('resize',sizeViewport);
window.visualViewport?.addEventListener('resize',sizeViewport);
function currentMarkdown() {
 if(!ready || !editor) return null;
 const value=editor.getMarkdown();
 return value===canonicalInitial?originalMarkdown:value;
}
function reportChange() {
 clearTimeout(changeTimer);
 clearTimeout(maximumTimer); maximumTimer=undefined;
 const value=currentMarkdown();
 if(value!==null) Hibi.onChange(value);
 return value;
}
window.initialize=function(markdown,colors) {
 try {
  sizeViewport();
  ready=false; originalMarkdown=markdown;
  for(const [key,value] of Object.entries(colors)) document.documentElement.style.setProperty('--'+key,value);
  editor=new toastui.Editor({el:document.getElementById('editor'),initialValue:markdown,initialEditType:'wysiwyg',hideModeSwitch:true,language:'de-DE',height:'100%',minHeight:'0px',autofocus:false,usageStatistics:false,placeholder:'Was möchtest du festhalten?',toolbarItems:[['heading','bold','italic','strike'],['quote','ul','ol','task'],['link']],events:{change(){if(ready){clearTimeout(changeTimer);changeTimer=setTimeout(reportChange,300);if(maximumTimer===undefined)maximumTimer=setTimeout(reportChange,1000);}}}});
  canonicalInitial=editor.getMarkdown(); ready=true;
  function labelMore() {
   const more=document.querySelector('.toastui-editor-defaultUI-toolbar button.more');
   const menu=document.querySelector('.toastui-editor-dropdown-toolbar');
   if(menu)menu.id='hibi-formatting-overflow';
   if(more){more.setAttribute('aria-label','Weitere Formatierungen');more.setAttribute('aria-haspopup','true');more.setAttribute('aria-controls','hibi-formatting-overflow');more.setAttribute('aria-expanded',String(!!menu&&menu.getBoundingClientRect().width>0));}
  }
  labelMore();
  if(typeof MutationObserver!=='undefined') {
   const observer=new MutationObserver(labelMore);
   observer.observe(document.querySelector('.toastui-editor-defaultUI'),{childList:true,subtree:true,attributes:true,attributeFilter:['style','class','hidden']});
  }
  Hibi.onReady(); return true;
 } catch(error) {ready=false; console.error('Hibi editor initialization: '+error.message); Hibi.onError('Der Schreibbereich konnte nicht geladen werden.');return false;}
};
// A native caller can discard its read response; draft delivery must still be independent.
window.getMarkdown=reportChange;
window.addEventListener('blur',()=>{if(ready)reportChange();});
window.addEventListener('pagehide',()=>{if(ready)reportChange();});
document.addEventListener('visibilitychange',()=>{if(ready && document.visibilityState==='hidden')reportChange();});
