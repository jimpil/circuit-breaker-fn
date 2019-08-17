# circuit-breaker-fn

Reusable primitives for implementing the **cicruit-breaker** pattern in Clojure, plus two concrete implementations (`fn`/`agent`).

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
- Error handler (how to react to errors)
- Processing handler (the process we're protecting against) 

Looking at the `circuit-breaker-fn.primitives` namespace, we can see there are exactly three Vars:

- `cb-init` (static map)
- `cb-error-handler` (is the right error-handler after partially binding all but the last arg)
- `cb-wrap-handler`  (returns the right processing-handler)

These are needed for building a complete circuit-breaker component. 

### Components
Using the primitives described in the previous section, we can start building more meaningful constructs.
 
 Obviously, the most general/reusable construct we can apply circuit-breaking semantics to, is the function itself:
 
#### cb-fn [f & cb-opts]
Returns a function that wraps \<f\> with circuit-breaker semantics.

#### cb-opts
- `fail-limit`: How many Exceptions (within <time-window>) before transitioning from _CLOSED_ => _OPEN_. 
- `fail-window`: Time window (in `fail-window-unit`) in which `fail-limit` has an effect.
- `fail-window-unit`: One of `#{:micros :millis :seconds :minutes :hours :days}`.
- `open-timeout`: How long (in `timeout-unit`) to wait before transitioning from _OPEN_ => _HALF-OPEN_.
- `timeout-unit`: Same as `fail-window-unit.
- `success-limit`: How many successful calls before transitioning from _HALF-OPEN_ => _CLOSED_.
- `success-block`: Function (or positive integer) expected to produce an artificial delay (via `Thread/sleep`) after each successful call to `f`. If provided MUST be accounted for in `fail-window`!
- `drop-fn`: Function to handle all requests while in _OPEN_ state (arg-list per <f>). If a default value makes sense in your domain this is your chance to use it.
- `ex-fn`: Function of 3 args to be called last when handling errors. Takes the Exception itself (do NOT rethrow it!), the time it occurred (per `System/nanoTime`) & the current fail count.
- `locking?`: Boolean indicating whether the handler that wraps `f` should run after acquiring a lock (will wait for it). 
- `try-locking?`: Boolean indicating whether the handler that wraps `f` should run after trying to acquire a lock (will NOT run if it fails to acquire one).

#### cb-agent [init & cb-opts]
Returns a vector with two elements:
 - an agent implementing circuit-breaker semantics. Certain limitations apply - see doc-string for details
 - a function to wrap any function sent to the returned agent
 
#### cb-opts 
Same options as per `cb-fn`, apart from the last two (`locking?`/`try-locking?`), simply because these don't make sense in the context of an agent (which has its own atomic-transition semantics).

All options are spec-ed and validated (see `validation.clj`). If validation fails an `ex-info` carrying the result of `s/explain-data` is thrown. 
 
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
