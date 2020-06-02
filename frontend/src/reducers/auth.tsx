import { Authenticate, Unauthenticate } from '../actions/auth';
import { AUTHENTICATE, UNAUTHENTICATE } from '../constants';
import { Auth } from '../types';

export default function authReducer(
  state: Auth = {
    isAuthenticated: null,
  },
  action: Authenticate | Unauthenticate
): Auth {
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
