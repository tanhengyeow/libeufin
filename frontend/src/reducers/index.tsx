import { Authenticate, Unauthenticate } from '../actions/auth';
import { AUTHENTICATE, UNAUTHENTICATE } from '../constants';
import { Store } from '../types';

export default function rootReducer(
  state: Store = {
    isAuthenticated: false,
  },
  action: Authenticate | Unauthenticate
): Store {
  switch (action.type) {
    case AUTHENTICATE:
      return {
        ...state,
        isAuthenticated: true,
      };
    case UNAUTHENTICATE:
      return { ...state, isAuthenticated: false };
    default:
      return state;
  }
}
