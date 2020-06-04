/* eslint-disable @typescript-eslint/no-explicit-any */
import { ThunkDispatch as Dispatch } from 'redux-thunk';
import { Base64 } from 'js-base64';
import * as constants from '../constants';

export interface Authenticate {
  type: constants.AUTHENTICATE;
}
const authenticate = (): Authenticate => {
  return {
    type: constants.AUTHENTICATE,
  };
};

export interface Unauthenticate {
  type: constants.UNAUTHENTICATE;
}
const unauthenticate = (): Unauthenticate => {
  return {
    type: constants.UNAUTHENTICATE,
  };
};

export type AuthenticationAction = Authenticate | Unauthenticate;

export const login = (nexusURL: string, username: string, password: string) => {
  return async (dispatch: Dispatch<AuthenticationAction, {}, any>) => {
    if (nexusURL && username && password) {
      await fetch(`/user`, {
        headers: new Headers({
          Authorization: `Basic ${Base64.encode(`${username}:${password}`)}`,
        }),
      })
        .then((response) => {
          if (response.ok) {
            return response.json();
          }
          throw new Error('Error connecting to server');
        })
        .then(async () => {
          await window.localStorage.setItem('authenticated', 'true');
          await window.localStorage.setItem(
            'authHeader',
            `${Base64.encode(`${username}:${password}`)}`
          );
          dispatch(authenticate());
        })
        .catch((err) => {
          console.log(err);
        });
    }
  };
};
export const logout = () => {
  return async (dispatch: Dispatch<AuthenticationAction, {}, any>) => {
    await window.localStorage.setItem('authenticated', 'false');
    await window.localStorage.setItem('authHeader', '');
    dispatch(unauthenticate());
  };
};

export const checkAuthentication = () => {
  return async (dispatch: Dispatch<AuthenticationAction, {}, any>) => {
    const auth = await window.localStorage.getItem('authenticated');
    const formattedAuth = typeof auth === 'string' ? JSON.parse(auth) : null;

    if (formattedAuth) {
      dispatch(authenticate());
    } else {
      dispatch(unauthenticate());
    }
  };
};
