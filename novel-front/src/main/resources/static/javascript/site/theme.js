// Apply before paint. Storage may be unavailable in private browsing.
(()=>{try{const value=localStorage.getItem('novel.site.theme');const dark=value==='dark'||(!value&&matchMedia('(prefers-color-scheme: dark)').matches);document.documentElement.dataset.theme=dark?'dark':'light';}catch{document.documentElement.dataset.theme='light';}})();
