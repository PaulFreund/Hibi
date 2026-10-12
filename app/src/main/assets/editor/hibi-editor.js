let editor;
window.initialize=function(markdown,colors) {
 for(const [key,value] of Object.entries(colors)) document.documentElement.style.setProperty('--'+key,value);
 editor=new toastui.Editor({el:document.getElementById('editor'),initialValue:markdown,initialEditType:'wysiwyg',hideModeSwitch:true,language:'de-DE',height:'auto',minHeight:'300px',autofocus:false,usageStatistics:false,placeholder:'Was möchtest du festhalten?',toolbarItems:[['heading','bold','italic','strike'],['quote','ul','ol','task'],['link']],events:{change(){Hibi.onChange(editor.getMarkdown());}}});
};
window.getMarkdown=()=>editor?editor.getMarkdown():'';
