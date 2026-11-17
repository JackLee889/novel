// Original typographic book jackets, used ONLY by the isolated preview fixture.
const fs = require('node:fs'); const path = require('node:path');
const target = path.join(__dirname, 'assets'); fs.mkdirSync(target, {recursive:true});
const titles = ['雨港手记','山海拾光','长街灯未眠','风起北境','星河来信','人间慢行','月落长安','深海回声'];
const palette = [['#204a42','#dce9cc'],['#b9bba1','#24352b'],['#865943','#f2e7d6'],['#20363f','#e4dac1'],['#656987','#f4ebd9'],['#d8cfc0','#324838'],['#554f49','#ead8ba'],['#305868','#e4ded0']];
titles.forEach((title,n) => {
 const [bg,fg]=palette[n];
 const text = [...title].map((c,i)=>`<tspan x="110" dy="${i?38:0}">${c}</tspan>`).join('');
 const svg=`<svg xmlns="http://www.w3.org/2000/svg" width="300" height="420" viewBox="0 0 300 420"><rect width="300" height="420" fill="${bg}"/><rect x="19" y="19" width="262" height="382" fill="none" stroke="${fg}" opacity=".4"/><path d="M0 300Q90 230 300 330V420H0Z" fill="${fg}" opacity=".10"/><path d="M0 355Q155 275 300 340" fill="none" stroke="${fg}" opacity=".25"/><text x="38" y="54" font-family="sans-serif" font-size="10" letter-spacing="3" fill="${fg}">ORIGINAL STORIES</text><text x="110" y="120" font-size="33" font-family="serif" fill="${fg}">${text}</text><text x="205" y="172" font-size="11" font-family="sans-serif" fill="${fg}" writing-mode="vertical-rl">${['南岸','青禾','路遥知','林深'][n%4]} 著</text><text x="38" y="375" font-family="sans-serif" font-size="10" letter-spacing="2" fill="${fg}">小说精品屋 · 原创演示</text></svg>`;
 fs.writeFileSync(path.join(target,`cover-${n}.svg`),svg);
});
