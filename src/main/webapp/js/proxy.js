/* ESI Proxy Module */
(function(){
var esiProxy = angular.module('esiProxy', [
  'ngRoute',
  'ngResource',
  'esiProxyDialog',
  'esiProxyRemoteServices',
  'esiProxyServicesWS',
  'esiProxyMain',
  'esiProxyConnections'
  ]);

// Capture any authorization errors before we process the rest of the window location
var searchParams = window.location.search;
var auth_error = null;
if (searchParams && searchParams.length > 1) {
  var vals = searchParams.substr(1).split('&');
  for (var i = 0; i < vals.length; i++) {
    var next = vals[i].split('=');
    if (next[0] == 'auth_error') {
      auth_error = decodeURIComponent(next[1].replace(/\+/g,' '));
      break;
    }
  }
}

esiProxy.config(['$routeProvider',
  function($routeProvider) {
    // Set up routes
    $routeProvider.
      when('/main', {
        templateUrl: 'partials/main.html',
        controller: 'MainCtrl'
      }).
      when('/connections', {
        templateUrl: 'partials/connections.html',
        controller: 'ConnectionsCtrl'
      }).
      otherwise({
        redirectTo: '/main'
      });
  }]);

/* Add scrolling directive to handle hash scrolling. */
/* nicked from here: http://stackoverflow.com/questions/14712223/how-to-handle-anchor-hash-linking-in-angularjs */
esiProxy.directive('scrollTo', function ($location, $anchorScroll) {
  return function(scope, element, attrs) {

    element.bind('click', function(event) {
        event.stopPropagation();
        var off = scope.$on('$locationChangeStart', function(ev) {
            off();
            ev.preventDefault();
        });
        var location = attrs.scrollTo;
        $location.hash(location);
        $anchorScroll();
    });
}});

/* Inband controller for setting the version for the page */
esiProxy.controller('ProxyVersionCtrl', ['$scope', 'ReleaseService',
  function($scope, ReleaseService) {
    ReleaseService.buildDate().then(function (val) {
      $scope.$apply(function() {
        $scope.esiProxyBuildDate = val;
      });
    });
    ReleaseService.version().then(function (val) {
      $scope.$apply(function() {
        $scope.esiProxyVersion = val;
      });
    });
}]);

/* Inband controller for setting authentication status and other container menu settings. */
esiProxy.controller('ProxyAuthCtrl', ['$scope', '$route', '$timeout', 'UserCredentialsService', 'AccountWSService', 'DialogService',
  function($scope, $route, $timeout, UserCredentialsService, AccountWSService, DialogService) {
    $scope.$route = $route;
    // Apply menu filter when specified
    $scope.menufilter = function(value, index) {
      return angular.isDefined(value.filter) ? value.filter() : true;
    };
    // Define menus
    $scope.proxymenus = [
                          { menuID: 1,
                            title: 'Front Page',
                            display: 'Home',
                            urlPrefix: '/main/',
                            link: '#/main'

                          },
                          { menuID: 2,
                            title: 'Connections',
                            display: 'Connections',
                            urlPrefix: '/connections/',
                            filter: function() { return $scope.userSource != null },
                            link: '#/connections'
                          }
                          ];
    // Set up user credential management
    $scope.userInfo = UserCredentialsService.getUser();
    $scope.userSource = UserCredentialsService.getUserSource();
    $scope.$on('UserInfoChange', function(event, ui) { $scope.userInfo = ui; });
    $scope.$on('UserSourceChange', function(event, us) { $scope.userSource = us; });
    // Check for authentication error and post an appropriate dialog
    if (auth_error !== null) {
      $timeout(function () { DialogService.simpleErrorMessage(auth_error, 20) }, 1);
    }
  }]);

})();
