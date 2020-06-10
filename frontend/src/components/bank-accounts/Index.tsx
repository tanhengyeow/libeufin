import React, { useState } from 'react';

const BankAccounts = () => {
  const [connectionsList, setConnectionsList] = useState([]);

  React.useEffect(() => {
    const fetchBankConnections = async () => {
      const authHeader = await window.localStorage.getItem('authHeader');
      await fetch(`/bank-connections`, {
        headers: new Headers({
          Authorization: `Basic ${authHeader}`,
        }),
      })
        .then((response) => {
          if (response.ok) {
            return response.json();
          }
          throw 'Cannot fetch bank connections';
        })
        .then((response) => {
          setConnectionsList(response.bankConnections);
        })
        .catch((err) => {
          throw new Error(err);
        });
    };
    fetchBankConnections();
  }, []);

  return (
    <>
      <h1>Bank Accounts</h1>
      <h2>Bank connections</h2>
      {connectionsList
        ? connectionsList.map((bankConnection) => (
            <div>
              <p>Name: {bankConnection['name']}</p>
              <p>Type: {bankConnection['type']}</p>
            </div>
          ))
        : null}
    </>
  );
};

export default BankAccounts;
