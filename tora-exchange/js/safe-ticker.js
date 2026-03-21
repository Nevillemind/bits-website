// Safe crypto ticker — no innerHTML (NEW-02 + NEW-03 security fix)
// Replaces vulnerable innerHTML pattern across all pages with safe DOM construction.
// Container ID: "live-ticker" — must exist in the page HTML.
(function() {
  const symbols = {
    bitcoin:'BTC', ethereum:'ETH', tether:'USDT', ripple:'XRP',
    solana:'SOL', binancecoin:'BNB', usd_coin:'USDC', dogecoin:'DOGE',
    cardano:'ADA', chainlink:'LINK', avalanche_2:'AVAX', polkadot:'DOT'
  };
  const ids = Object.keys(symbols).join(',');
  const fmt = n => n >= 1000 ? '$'+n.toLocaleString('en-US',{maximumFractionDigits:0}) : n >= 1 ? '$'+n.toFixed(2) : '$'+n.toFixed(4);

  let lastData = {
    bitcoin:{usd:82500,usd_24h_change:1.2}, ethereum:{usd:2180,usd_24h_change:-0.8},
    tether:{usd:1.00,usd_24h_change:0.01}, ripple:{usd:2.28,usd_24h_change:3.1},
    solana:{usd:128,usd_24h_change:2.4}, binancecoin:{usd:580,usd_24h_change:0.6},
    usd_coin:{usd:1.00,usd_24h_change:0.0}, dogecoin:{usd:0.175,usd_24h_change:-1.3},
    cardano:{usd:0.78,usd_24h_change:1.9}, chainlink:{usd:14.20,usd_24h_change:0.5},
    avalanche_2:{usd:22.50,usd_24h_change:-0.4}, polkadot:{usd:4.85,usd_24h_change:1.1}
  };

  function buildSafeTicker(data) {
    var track = document.getElementById('live-ticker');
    if (!track) return;
    // Safe clear — textContent, not innerHTML
    track.textContent = '';
    for (var loop = 0; loop < 2; loop++) {
      for (var _i = 0, _a = Object.entries(symbols); _i < _a.length; _i++) {
        var id = _a[_i][0], sym = _a[_i][1];
        if (!data[id]) continue;
        var price = fmt(data[id].usd);
        var change = data[id].usd_24h_change || 0;
        var isPos = change >= 0;

        var item = document.createElement('span');
        item.className = 'ticker-item';

        var label = document.createElement('span');
        label.className = 'ticker-label';
        label.textContent = sym;

        var value = document.createElement('span');
        value.className = 'ticker-value';
        value.appendChild(document.createTextNode(price + ' '));

        var chg = document.createElement('span');
        chg.className = isPos ? 'ticker-up' : 'ticker-down';
        chg.textContent = (isPos ? '+' : '') + change.toFixed(1) + '% ' + (isPos ? '\u25B2' : '\u25BC');
        value.appendChild(chg);

        item.appendChild(label);
        item.appendChild(document.createTextNode(' '));
        item.appendChild(value);
        track.appendChild(item);

        var dot = document.createElement('span');
        dot.className = 'ticker-dot';
        track.appendChild(dot);
      }
    }
  }

  buildSafeTicker(lastData);

  async function fetchPrices() {
    try {
      var res = await fetch('https://api.coingecko.com/api/v3/simple/price?ids=' + ids + '&vs_currencies=usd&include_24hr_change=true');
      if (res.ok) { lastData = await res.json(); buildSafeTicker(lastData); }
    } catch(e) { /* keep showing cached data */ }
  }

  setTimeout(fetchPrices, 5000);
  setInterval(fetchPrices, 120000);
})();
