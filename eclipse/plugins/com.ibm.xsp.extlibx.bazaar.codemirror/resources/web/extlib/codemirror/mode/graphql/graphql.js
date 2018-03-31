dojo.provide("extlib.codemirror.mode.graphql.graphql");

CodeMirror.defineMode("graphql", function() {
  return {
    token: function(stream, state) {
      var eol = stream.eol();

      state.afterSection = false;

      var ch = stream.next();

      if (ch === "#") {
        state.position = "comment";
        stream.skipToEnd();
        return "comment";
      } 
      
      return state.position;
    },

    startState: function() {
      return {
        position : "def"
      };
    }

  };
});
