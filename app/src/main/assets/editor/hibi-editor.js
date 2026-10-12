let editor, originalMarkdown='', canonicalInitial='', ready=false, changeTimer;
function currentMarkdown() {
 if(!ready || !editor) return null;
 const value=editor.getMarkdown();
 return value===canonicalInitial?originalMarkdown:value;
}
function reportChange() {
 clearTimeout(changeTimer);
 const value=currentMarkdown();
 if(value!==null) Hibi.onChange(value);
}
window.initialize=function(markdown,colors) {
 try {
  ready=false; originalMarkdown=markdown;
  for(const [key,value] of Object.entries(colors)) document.documentElement.style.setProperty('--'+key,value);
  editor=new toastui.Editor({el:document.getElementById('editor'),initialValue:markdown,initialEditType:'wysiwyg',hideModeSwitch:true,language:'de-DE',height:'100%',minHeight:'0px',autofocus:false,usageStatistics:false,placeholder:'Was möchtest du festhalten?',toolbarItems:[['heading','bold','italic','strike'],['quote','ul','ol','task'],['link']],events:{change(){if(ready){clearTimeout(changeTimer);changeTimer=setTimeout(reportChange,300);}}}});
  canonicalInitial=editor.getMarkdown(); ready=true; Hibi.onReady();
 } catch(error) {ready=false; Hibi.onError('Der Schreibbereich konnte nicht geladen werden.');}
};
window.getMarkdown=()=>{clearTimeout(changeTimer);return currentMarkdown();};
window.addEventListener('blur',()=>{if(ready)reportChange();});
