/*
 This file is part of GNU Taler
 (C) 2020 Taler Systems S.A.

 GNU Taler is free software; you can redistribute it and/or modify it under the
 terms of the GNU General Public License as published by the Free Software
 Foundation; either version 3, or (at your option) any later version.

 GNU Taler is distributed in the hope that it will be useful, but WITHOUT ANY
 WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 A PARTICULAR PURPOSE.  See the GNU General Public License for more details.

 You should have received a copy of the GNU General Public License along with
 GNU Taler; see the file COPYING.  If not, see <http://www.gnu.org/licenses/>
 */

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
          } else if (response.status === 403) {
            throw new Error('Invalid credentials');
          }
          throw new Error('Cannot connect to server');
        })
        .then(async () => {
          await window.localStorage.setItem('authenticated', 'true');
          await window.localStorage.setItem(
            'authHeader',
            `${Base64.encode(`${username}:${password}`)}`
          );
          dispatch(authenticate());
        })
        .catch((err: Error) => {
          throw err;
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
