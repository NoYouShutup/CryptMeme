var fails = 0;

function ajax(url, target, refresh) {
  // native XMLHttpRequest object
  if (window.XMLHttpRequest) {
    req = new XMLHttpRequest();
    req.onreadystatechange = function() {ajaxDone(url, target, refresh);};
    req.open("GET", url, true);
    req.send(null);
    // IE/Windows ActiveX version
  } else if (window.ActiveXObject) {
    req = new ActiveXObject("Microsoft.XMLDOM");
    if (req) {
      req.onreadystatechange = function() {ajaxDone(target);};
      req.open("GET", url, true);
      req.send(null);
    }
  }
}

function ajaxDone(url, target, refresh) {
  // only if req is "loaded"
  if (req.readyState == 4) {
    // only if "OK"
    if (req.status == 200) {
      fails = 0;
      results = req.responseText;
      document.getElementById(target).innerHTML = results;
      //document.getElementsbyClassName("hideifdown").style.display="block";
    } else if (fails == 0) {
      // avoid spurious message if cancelled by user action
      fails++;
    } else {
      document.getElementById(target).innerHTML = failMessage;
      //document.getElementByClassName("hideifdown").style.display="none";
    }
    setTimeout(function() {ajax(url, target, refresh);}, refresh);
  }
}
