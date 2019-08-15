# circuit-breaker-fn

Reusable **cicruit-breaker** primitives for Clojure.

<p align="center">
  <img src="https://cloudandmobileblogcom.files.wordpress.com/2017/04/states.png?w=700"/>
</p>

## Why 

- [great article](https://docs.microsoft.com/en-us/azure/architecture/patterns/circuit-breaker)

- [great book](https://pragprog.com/book/mnee/release-it) (section 5.2)

## Where 

FIXME

## How
### Primitives
Three things are needed in order to implement a circuit-breaker:

- State (managed internally)
- Error handler (how to react on errors)
- Processing handler (the process we're protecting against) 

Looking at the `circuit-breaker-fn.primitives` namespace, we can see there are exactly three Vars:

- `cb-init` (static map)
- `cb-error-handler` (is the right error-handler after partially binding all but the last arg)
- `cb-wrap-handler`  (returns the right processing-handler) 

### Components
Using the primitives described in the previous section, we can start building more meaningful constructs.
 
 Obviously, the most general/reusable construct we can apply circuit-breaking semantics to, is the function itself:
 
#### cb-fn [f cb-opts]
Returns a function that wraps \<f\> with circuit-breaker semantics.

#### cb-agent [init & cb-opts]
Returns an agent implementing circuit-breaker semantics. Certain limitations apply on the returned agent - see doc-string for details.
 

## License

Copyright © 2019 Dimitrios Piliouras

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
